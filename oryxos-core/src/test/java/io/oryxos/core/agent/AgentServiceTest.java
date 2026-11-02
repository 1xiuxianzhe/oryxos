package io.oryxos.core.agent;

import io.oryxos.core.audit.AgentRunAuditor;
import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.react.ModelCallException;
import io.oryxos.core.react.ProcessingTimeoutException;
import io.oryxos.core.react.ReActLoop;
import io.oryxos.core.react.ReActResult;
import io.oryxos.core.react.StopReason;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.session.SessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AgentService 验收测试：AgentContext 生命周期（含异常路径必须清理）、
 * 会话持久化（成功与失败都要落库）、以及<b>请求级审计</b>
 * （成功/达上限/超时/模型报错/配置错误五种终态都必须留下记录）。
 */
class AgentServiceTest {

    private Profile profile;
    private ProfileRegistry profiles;
    private SessionStore store;
    private SessionManager sessions;
    private ReActLoop loop;
    private AgentRunAuditor runAuditor;
    private AgentService service;

    @BeforeEach
    void setUp() {
        profile = new Profile();
        profile.setName("ops-agent");
        profile.setIdentity("你是运维助手");
        Profile.ProviderConfig provider = new Profile.ProviderConfig();
        provider.setName("deepseek");
        provider.setModel("deepseek-chat");
        profile.setProvider(provider);

        profiles = new ProfileRegistry();
        profiles.registerProfile("ops-agent", profile);

        store = mock(SessionStore.class);
        when(store.find(anyString())).thenReturn(Optional.empty());
        sessions = new SessionManager(store);

        loop = mock(ReActLoop.class);
        runAuditor = mock(AgentRunAuditor.class);
        service = new AgentService(profiles, sessions, loop, runAuditor);
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    private ReActResult result(String text, StopReason reason, int iterations, int toolCalls) {
        return new ReActResult(text, reason, iterations, toolCalls, new TokenUsage(10, 5, 15), 12L);
    }

    // ==================== AgentContext 生命周期 ====================

    @Test
    void 处理期间_AgentContext可取到当前Profile与runId() {
        AtomicReference<Profile> seenProfile = new AtomicReference<>();
        AtomicReference<String> seenRunId = new AtomicReference<>();
        when(loop.runResult(anyString(), any(), anyString(), any())).thenAnswer(invocation -> {
            seenProfile.set(AgentContext.current());
            seenRunId.set(AgentContext.currentRunId());
            return result("好的", StopReason.FINAL_ANSWER, 1, 0);
        });

        String reply = service.process("s-1", "ops-agent", "cli", "u-1", "你好");

        assertThat(reply).isEqualTo("好的");
        assertThat(seenProfile.get()).isSameAs(profile);
        assertThat(seenRunId.get()).isNotBlank();
        // 出口必须清理，否则复用线程的下一个请求会拿到别人的 Profile
        assertThat(AgentContext.current()).isNull();
        assertThat(AgentContext.currentRunId()).isNull();
    }

    @Test
    void 处理抛异常_ProfileContext也必须被清掉() {
        when(loop.runResult(anyString(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> service.process("s-1", "ops-agent", "cli", "u-1", "你好"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");

        assertThat(AgentContext.current()).isNull();
        assertThat(AgentContext.currentRunId()).isNull();
        assertThat(MDC.get(AgentService.MDC_RUN_ID)).isNull();
    }

    @Test
    void 处理期间_MDC里的runId与写进审计的runId一致() {
        AtomicReference<String> mdcDuringRun = new AtomicReference<>();
        when(loop.runResult(anyString(), any(), anyString(), any())).thenAnswer(invocation -> {
            mdcDuringRun.set(MDC.get(AgentService.MDC_RUN_ID));
            return result("好的", StopReason.FINAL_ANSWER, 1, 0);
        });

        service.process("s-1", "ops-agent", "cli", "u-1", "你好");

        ArgumentCaptor<String> runIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(runAuditor).recordStarted(runIdCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
        // 日志里的 runId 必须等于落库的 runId，否则"凭日志还原一次处理"就对不上账
        assertThat(mdcDuringRun.get()).isEqualTo(runIdCaptor.getValue());
        assertThat(mdcDuringRun.get()).isNotBlank();
    }

    // ==================== 会话持久化（成功与失败都要落库） ====================

    @Test
    void 处理结束_会话被持久化() {
        when(loop.runResult(anyString(), any(), anyString(), any())).thenAnswer(invocation -> {
            Session session = invocation.getArgument(1);
            session.addMessage(new Session.Message("assistant", "好的"));
            return result("好的", StopReason.FINAL_ANSWER, 1, 0);
        });

        service.process("s-1", "ops-agent", "cli", "u-1", "你好");

        verify(store).save(any(Session.class));
    }

    @Test
    void 处理超时_会话仍然被持久化() {
        when(loop.runResult(anyString(), any(), anyString(), any()))
                .thenThrow(new ProcessingTimeoutException(60, 2, 1, new TokenUsage(30, 10, 40)));

        assertThatThrownBy(() -> service.process("s-1", "ops-agent", "cli", "u-1", "你好"))
                .isInstanceOf(ProcessingTimeoutException.class);

        // FR-009 / US3 场景 3：失败路径也要留下已累积的历史
        verify(store).save(any(Session.class));
    }

    @Test
    void 复用已存在会话_不新建() {
        Session existing = sessions.create("s-1", "ops-agent", "cli", "u-1");
        existing.addMessage(new Session.Message("user", "上一轮的问题"));
        when(loop.runResult(anyString(), any(), anyString(), any()))
                .thenReturn(result("好的", StopReason.FINAL_ANSWER, 1, 0));

        service.process("s-1", "ops-agent", "cli", "u-1", "这一轮的问题");

        Session after = sessions.get("s-1");
        assertThat(after).isSameAs(existing);
        assertThat(after.getMessages()).hasSize(1);   // mock 循环不会真的追加消息
    }

    @Test
    void sessionId为空_新建会话() {
        when(loop.runResult(anyString(), any(), anyString(), any()))
                .thenReturn(result("好的", StopReason.FINAL_ANSWER, 1, 0));

        service.process(null, "ops-agent", "cli", "u-1", "你好");

        assertThat(sessions.all()).hasSize(1);
    }

    // ==================== 请求级审计：五种终态都要留痕 ====================

    @Test
    void 成功处理_请求级审计记录问了什么与token汇总() {
        when(loop.runResult(anyString(), any(), anyString(), any())).thenReturn(
                new ReActResult("建议穿短袖", StopReason.FINAL_ANSWER, 2, 1,
                        new TokenUsage(120, 40, 160), 88L));

        service.process("s-1", "ops-agent", "cli", "u-1", "北京今天天气怎么样？");

        verify(runAuditor).recordStarted(anyString(), eq("s-1"), eq("ops-agent"), eq("cli"), eq("u-1"),
                eq("北京今天天气怎么样？"), eq("deepseek"), eq("deepseek-chat"));
        verify(runAuditor).recordFinished(anyString(), eq(StopReason.FINAL_ANSWER), eq(2), eq(1),
                eq(new TokenUsage(120, 40, 160)), isNull());
    }

    @Test
    void 达到最大轮数_请求级审计记录MAX_ITERATIONS且不算成功() {
        when(loop.runResult(anyString(), any(), anyString(), any())).thenReturn(
                result("已达到最大轮数（10），已停止", StopReason.MAX_ITERATIONS, 10, 10));

        service.process("s-1", "ops-agent", "cli", "u-1", "查天气");

        verify(runAuditor).recordFinished(anyString(), eq(StopReason.MAX_ITERATIONS), eq(10), eq(10),
                any(TokenUsage.class), isNull());
    }

    @Test
    void 处理超时_请求级审计记录TIMEOUT并带上已完成进度() {
        when(loop.runResult(anyString(), any(), anyString(), any()))
                .thenThrow(new ProcessingTimeoutException(60, 3, 2, new TokenUsage(150, 50, 200)));

        assertThatThrownBy(() -> service.process("s-1", "ops-agent", "cli", "u-1", "查天气"))
                .isInstanceOf(ProcessingTimeoutException.class);

        verify(runAuditor).recordFinished(anyString(), eq(StopReason.TIMEOUT), eq(3), eq(2),
                eq(new TokenUsage(150, 50, 200)), contains("60"));
    }

    @Test
    void 模型调用失败_请求级审计记录MODEL_ERROR() {
        when(loop.runResult(anyString(), any(), anyString(), any()))
                .thenThrow(new ModelCallException("connect timeout", new RuntimeException("connect timeout")));

        assertThatThrownBy(() -> service.process("s-1", "ops-agent", "cli", "u-1", "查天气"))
                .isInstanceOf(ModelCallException.class);

        verify(runAuditor).recordFinished(anyString(), eq(StopReason.MODEL_ERROR), eq(0), eq(0),
                isNull(), contains("connect timeout"));
    }

    @Test
    void 内部异常_请求级审计记录INTERNAL_ERROR() {
        when(loop.runResult(anyString(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("循环内部炸了"));

        assertThatThrownBy(() -> service.process("s-1", "ops-agent", "cli", "u-1", "查天气"))
                .isInstanceOf(IllegalStateException.class);

        verify(runAuditor).recordFinished(anyString(), eq(StopReason.INTERNAL_ERROR), eq(0), eq(0),
                isNull(), contains("循环内部炸了"));
    }

    @Test
    void Profile不存在_请求级审计也要留下配置错误记录() {
        assertThatThrownBy(() -> service.process("s-1", "ghost-agent", "cli", "u-1", "你好"))
                .isInstanceOf(AgentConfigurationException.class)
                .hasMessageContaining("ghost-agent");

        // 请求内容照常先落库（此时还不知道 provider/model）
        verify(runAuditor).recordStarted(anyString(), eq("s-1"), eq("ghost-agent"), eq("cli"), eq("u-1"),
                eq("你好"), isNull(), isNull());
        verify(runAuditor).recordFinished(anyString(), eq(StopReason.AGENT_CONFIG_ERROR), eq(0), eq(0),
                isNull(), contains("ghost-agent"));
    }

    @Test
    void 审计实现自身抛异常_不得影响业务结果() {
        doThrow(new RuntimeException("审计库挂了"))
                .when(runAuditor).recordStarted(any(), any(), any(), any(), any(), any(), any(), any());
        when(loop.runResult(anyString(), any(), anyString(), any()))
                .thenReturn(result("好的", StopReason.FINAL_ANSWER, 1, 0));

        assertThat(service.process("s-1", "ops-agent", "cli", "u-1", "你好")).isEqualTo("好的");
    }
}
