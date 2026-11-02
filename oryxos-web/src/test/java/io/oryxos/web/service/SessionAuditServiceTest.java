package io.oryxos.web.service;

import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.storage.entity.AgentRunEntity;
import io.oryxos.storage.entity.LlmCallEntity;
import io.oryxos.storage.entity.ToolInvocationEntity;
import io.oryxos.storage.repository.AgentRunRepository;
import io.oryxos.storage.repository.LlmCallRepository;
import io.oryxos.storage.repository.ToolInvocationRepository;
import io.oryxos.web.dto.SessionAuditDtos;
import io.oryxos.web.exception.SessionNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计聚合的验收：分组对不对、数字加得对不对、长结果截不截。
 *
 * <p>全部 mock 仓储——这里要验的是"拼装逻辑"，数据库往返由仓储自己的测试负责。</p>
 */
class SessionAuditServiceTest {

    private static final String SESSION = "s-1";
    private static final Instant BASE = Instant.parse("2026-10-21T10:00:00Z");

    private final SessionManager sessionManager = mock(SessionManager.class);
    private final AgentRunRepository agentRunRepository = mock(AgentRunRepository.class);
    private final LlmCallRepository llmCallRepository = mock(LlmCallRepository.class);
    private final ToolInvocationRepository toolInvocationRepository = mock(ToolInvocationRepository.class);

    private final SessionAuditService service = new SessionAuditService(
            sessionManager, agentRunRepository, llmCallRepository, toolInvocationRepository);

    @BeforeEach
    void setUp() {
        when(sessionManager.get(SESSION)).thenReturn(mock(Session.class));
    }

    private static AgentRunEntity run(String runId, String question, Instant startedAt, Instant completedAt) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setRunId(runId);
        entity.setSessionId(SESSION);
        entity.setUserMessage(question);
        entity.setProfileName("default");
        entity.setChannel("cli");
        entity.setProvider("deepseek");
        entity.setModel("deepseek-chat");
        entity.setStatus("FINISHED");
        entity.setStopReason("FINAL_ANSWER");
        entity.setSuccess(true);
        entity.setIterations(2);
        entity.setToolCallCount(1);
        entity.setPromptTokens(900);
        entity.setCompletionTokens(120);
        entity.setTotalTokens(1020);
        entity.setStartedAt(startedAt);
        entity.setCompletedAt(completedAt);
        return entity;
    }

    private static LlmCallEntity llm(String callId, String runId, Instant at, int prompt, int completion) {
        LlmCallEntity entity = new LlmCallEntity();
        entity.setCallId(callId);
        entity.setSessionId(SESSION);
        entity.setRunId(runId);
        entity.setProvider("deepseek");
        entity.setModel("deepseek-chat");
        entity.setPromptTokens(prompt);
        entity.setCompletionTokens(completion);
        entity.setTotalTokens(prompt + completion);
        entity.setLatencyMs(1200L);
        entity.setSuccess(true);
        entity.setStatus("SUCCESS");
        entity.setStartedAt(at);
        entity.setCompletedAt(at.plusMillis(1200));
        return entity;
    }

    private static ToolInvocationEntity tool(String id, String runId, String name,
                                             String parameters, String result, Instant at) {
        ToolInvocationEntity entity = new ToolInvocationEntity();
        entity.setInvocationId(id);
        entity.setSessionId(SESSION);
        entity.setRunId(runId);
        entity.setProfileName("default");
        entity.setToolName(name);
        entity.setParameters(parameters);
        entity.setResult(result);
        entity.setLatencyMs(340L);
        entity.setSuccess(true);
        entity.setStatus("SUCCESS");
        entity.setStartedAt(at);
        entity.setCompletedAt(at.plusMillis(340));
        return entity;
    }

    @Test
    void 按run分组并汇总token与耗时() {
        AgentRunEntity newer = run("r-2", "上海呢", BASE.plusSeconds(32), BASE.plusSeconds(35));
        AgentRunEntity older = run("r-1", "今天北京天气怎么样", BASE, BASE.plusSeconds(2));
        when(agentRunRepository.findBySessionIdOrderByStartedAtDesc(SESSION))
                .thenReturn(List.of(newer, older));

        // 故意乱序返回：组内必须按开始时间升序（轮次顺序）
        when(llmCallRepository.findBySessionId(SESSION)).thenReturn(List.of(
                llm("l-2", "r-1", BASE.plusSeconds(1), 400, 60),
                llm("l-3", "r-2", BASE.plusSeconds(32), 900, 120),
                llm("l-1", "r-1", BASE, 500, 60)));
        when(toolInvocationRepository.findBySessionId(SESSION)).thenReturn(List.of(
                tool("t-1", "r-1", "get_weather", "{\"city\":\"北京\"}", "晴 21℃", BASE)));

        SessionAuditDtos.SessionAudit audit = service.load(SESSION);

        assertThat(audit.sessionId()).isEqualTo(SESSION);
        assertThat(audit.totals().runs()).isEqualTo(2);
        assertThat(audit.totals().llmCalls()).isEqualTo(3);
        assertThat(audit.totals().toolCalls()).isEqualTo(1);
        assertThat(audit.totals().promptTokens()).isEqualTo(1800);
        assertThat(audit.totals().completionTokens()).isEqualTo(240);
        assertThat(audit.totals().totalTokens()).isEqualTo(2040);
        assertThat(audit.totals().durationMs()).isEqualTo(5000);

        // 最新的一次提问在最前，且挂着自己的明细
        SessionAuditDtos.RunView latest = audit.runs().get(0);
        assertThat(latest.runId()).isEqualTo("r-2");
        assertThat(latest.userMessage()).isEqualTo("上海呢");
        assertThat(latest.durationMs()).isEqualTo(3000);
        assertThat(latest.llmCalls()).hasSize(1);
        assertThat(latest.toolCalls()).isEmpty();

        SessionAuditDtos.RunView first = audit.runs().get(1);
        assertThat(first.runId()).isEqualTo("r-1");
        assertThat(first.userMessage()).isEqualTo("今天北京天气怎么样");
        assertThat(first.llmCalls()).extracting(SessionAuditDtos.LlmCallView::callId)
                .containsExactly("l-1", "l-2");
        assertThat(first.toolCalls()).hasSize(1);
        assertThat(first.toolCalls().get(0).toolName()).isEqualTo("get_weather");
        assertThat(first.toolCalls().get(0).resultTruncated()).isFalse();
    }

    @Test
    void 没有请求记录时返回空账本而不是报错() {
        when(agentRunRepository.findBySessionIdOrderByStartedAtDesc(SESSION)).thenReturn(List.of());
        when(llmCallRepository.findBySessionId(SESSION)).thenReturn(List.of());
        when(toolInvocationRepository.findBySessionId(SESSION)).thenReturn(List.of());

        SessionAuditDtos.SessionAudit audit = service.load(SESSION);

        assertThat(audit.runs()).isEmpty();
        assertThat(audit.totals().runs()).isZero();
        assertThat(audit.totals().totalTokens()).isZero();
        assertThat(audit.totals().durationMs()).isZero();
    }

    @Test
    void 会话不存在时抛404语义异常() {
        when(sessionManager.get("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.load("missing"))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void 超长工具结果被截断并显式标记() {
        String longResult = "x".repeat(SessionAuditService.RESULT_PREVIEW_CHARS + 500);
        String longParameters = "y".repeat(SessionAuditService.PARAMETER_PREVIEW_CHARS + 100);

        when(agentRunRepository.findBySessionIdOrderByStartedAtDesc(SESSION))
                .thenReturn(List.of(run("r-1", "读文件", BASE, BASE.plusSeconds(1))));
        when(llmCallRepository.findBySessionId(SESSION)).thenReturn(List.of());
        when(toolInvocationRepository.findBySessionId(SESSION)).thenReturn(List.of(
                tool("t-1", "r-1", "read_file", longParameters, longResult, BASE)));

        SessionAuditDtos.ToolCallView view = service.load(SESSION)
                .runs().get(0).toolCalls().get(0);

        assertThat(view.result()).hasSize(SessionAuditService.RESULT_PREVIEW_CHARS);
        assertThat(view.resultTruncated()).isTrue();
        assertThat(view.parameters()).hasSize(SessionAuditService.PARAMETER_PREVIEW_CHARS);
    }

    @Test
    void RUNNING中的请求不产生耗时也不报错() {
        AgentRunEntity running = run("r-9", "还没跑完", BASE, null);
        running.setStatus("RUNNING");
        running.setSuccess(null);
        running.setStopReason(null);

        when(agentRunRepository.findBySessionIdOrderByStartedAtDesc(SESSION))
                .thenReturn(List.of(running));
        when(llmCallRepository.findBySessionId(SESSION)).thenReturn(List.of());
        when(toolInvocationRepository.findBySessionId(SESSION)).thenReturn(List.of());

        SessionAuditDtos.SessionAudit audit = service.load(SESSION);

        assertThat(audit.runs()).hasSize(1);
        assertThat(audit.runs().get(0).durationMs()).isNull();
        assertThat(audit.runs().get(0).status()).isEqualTo("RUNNING");
        assertThat(audit.totals().durationMs()).isZero();
    }
}
