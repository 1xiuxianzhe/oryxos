package io.oryxos.boot;

import io.oryxos.boot.support.StubOpenAiServer;
import io.oryxos.boot.support.StubOpenAiServer.ToolCall;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.schedule.AgentScheduler;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.storage.entity.AgentRunEntity;
import io.oryxos.storage.repository.AgentRunRepository;
import io.oryxos.storage.repository.LlmCallRepository;
import io.oryxos.storage.repository.ToolInvocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 钟推链路的端到端对账（28 节 §二、§五）。
 *
 * <p>与 {@link HumanTriggerFlowIT} 唯一的区别是"谁发起"：这里不调 {@code AgentService}，
 * 而是调 {@link AgentScheduler#runOnce}——它自己取会话、自己把消息交给同一个入口。
 * 要验证的东西正好是"没区别"：账目、会话复用、失败隔离都与人推一致。</p>
 *
 * <p>不睡等 cron：{@code runOnce} 是 public 的，测试直接触发。等真实 cron 到点会让测试
 * 从"秒级"变成"分钟级"，且时间一到就红，谁都不想维护。</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SchedulerFlowIT {

    private static final Path WORKSPACE = prepareWorkspace();

    private static final StubOpenAiServer STUB = StubOpenAiServer.start(
            List.of(new ToolCall("read_file", "{\"path\":\"" + helloPath() + "\"}")),
            "定时摘要：HELLO.txt 里是 hello oryxos。");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("oryxos.workspace", WORKSPACE::toString);
        registry.add("oryxos.providers[0].name", () -> "stub");
        registry.add("oryxos.providers[0].api-key", () -> "stub-key");
        registry.add("oryxos.providers[0].base-url", STUB::baseUrl);
        registry.add("oryxos.sandbox.enabled", () -> "true");
        registry.add("oryxos.sandbox.allowed-paths[0]", WORKSPACE::toString);
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + WORKSPACE.resolve("scheduler.db"));
        registry.add("logging.level.io.oryxos", () -> "INFO");
    }

    @Autowired
    private AgentScheduler scheduler;

    @Autowired
    private ProfileRegistry profileRegistry;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @Autowired
    private LlmCallRepository llmCallRepository;

    @Autowired
    private ToolInvocationRepository toolInvocationRepository;

    @BeforeEach
    void resetStub() {
        STUB.reset();
    }

    @Test
    void 两次触发复用同一会话_每次都是一份完整的账() {
        Profile profile = profileRegistry.getProfile("smoke");
        Profile.ScheduleConfig schedule = profile.getSchedules().get(0);

        scheduler.runOnce(profile, schedule);
        assertThat(STUB.chatRequests()).isEqualTo(2);

        // 新的一次触发 = 新的一次对话，桩重新从"要工具"开始
        STUB.reset();
        scheduler.runOnce(profile, schedule);
        assertThat(STUB.chatRequests()).isEqualTo(2);

        Session session = sessionManager.getOrCreate(
                AgentScheduler.CHANNEL, AgentScheduler.USER_ID, "smoke");
        String sessionId = session.getSessionId();

        // ① 两次触发两行 agent_runs，且落在同一个 sessionId 上（复用而非每次新建）
        List<AgentRunEntity> runs =
                agentRunRepository.findBySessionIdOrderByStartedAtDesc(sessionId);
        assertThat(runs).hasSize(2);
        assertThat(runs).allMatch(run -> "FINAL_ANSWER".equals(run.getStopReason()));
        assertThat(runs).allMatch(run -> "scheduler".equals(run.getChannel()));

        // ② 每次触发两轮模型调用（要工具 + 给答案），共 4 条
        assertThat(llmCallRepository.findBySessionId(sessionId)).hasSize(4);
        // ③ 每次触发一次工具调用，共 2 条
        assertThat(toolInvocationRepository.findBySessionId(sessionId)).hasSize(2);
    }

    @Test
    void 单次触发的模型失败不抛出_调度器继续运行() {
        Profile profile = profileRegistry.getProfile("smoke");
        Profile.ScheduleConfig schedule = profile.getSchedules().get(0);
        String sessionId = sessionManager
                .getOrCreate(AgentScheduler.CHANNEL, AgentScheduler.USER_ID, "smoke")
                .getSessionId();

        STUB.failNextChat();

        // 关键：异常不能冒到调度线程——否则这条 cron 从此再也不会触发
        assertThatCode(() -> scheduler.runOnce(profile, schedule))
                .doesNotThrowAnyException();

        List<AgentRunEntity> runs =
                agentRunRepository.findBySessionIdOrderByStartedAtDesc(sessionId);
        assertThat(runs).anyMatch(run -> "MODEL_ERROR".equals(run.getStopReason()));

        // 失败也要留痕：否则"模型挂了"和"根本没调模型"在事后无法区分
        assertThat(llmCallRepository.findBySessionId(sessionId))
                .anyMatch(call -> !Boolean.TRUE.equals(call.getSuccess())
                        && call.getErrorMessage() != null);

        // 下一次触发照常
        assertThatCode(() -> scheduler.runOnce(profile, schedule))
                .doesNotThrowAnyException();
    }

    // ---------- 工作区 ----------

    private static Path prepareWorkspace() {
        try {
            Path dir = Files.createTempDirectory("oryxos-scheduler-flow-");
            Files.createDirectories(dir.resolve("profiles"));
            Files.writeString(dir.resolve("profiles/smoke.yaml"), """
                    description: 钟推链路验收用 Agent
                    identity: 定时汇总的助手
                    provider:
                      name: stub
                      model: stub-model
                    tools:
                      - name: read_file
                    settings:
                      max_iterations: 5
                      max_history_turns: 10
                    schedules:
                      - id: daily-brief
                        cron: "0 0 3 * * *"
                        message: "读 HELLO.txt 并给出摘要"
                    """, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("HELLO.txt"), "hello oryxos", StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("准备工作区失败", e);
        }
    }

    private static String helloPath() {
        return WORKSPACE.resolve("HELLO.txt").toString().replace('\\', '/');
    }
}
