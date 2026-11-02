package io.oryxos.storage.repository;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.core.react.StopReason;
import io.oryxos.storage.audit.JpaAgentRunAuditor;
import io.oryxos.storage.audit.JpaLlmCallAuditor;
import io.oryxos.storage.audit.JpaToolInvocationAuditor;
import io.oryxos.storage.entity.AgentRunEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agent_runs 请求级审计表验收测试。
 *
 * <p>后台要能回答："这次请求问了什么、用的哪个模型、花了多少 token、跑了几轮、
 * 调了几次工具、成没成、没成为什么"——所以这些列必须真实存在且能往返读写。
 * 建表走手工脚本 {@code db/schema.sql}，并强制 flush + clear 走真实数据库往返。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan("io.oryxos.storage.entity")
@EnableJpaRepositories("io.oryxos.storage.repository")
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql"
})
class AgentRunRepositoryTest {

    /**
     * 上下文启动前先删掉旧库文件，保证每次测的都是当前 {@code db/schema.sql} 建出的全新库。
     */
    @DynamicPropertySource
    static void sqliteDatabase(DynamicPropertyRegistry registry) {
        Path dbFile = Path.of("target", "agent-runs-test.db").toAbsolutePath();
        try {
            Files.deleteIfExists(dbFile);
        } catch (IOException e) {
            throw new IllegalStateException("无法清理测试数据库: " + dbFile, e);
        }
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile);
    }

    @SpringBootApplication
    static class TestApplication {
    }

    @Autowired
    private AgentRunRepository repository;

    @Autowired
    private LlmCallRepository llmCallRepository;

    @Autowired
    private ToolInvocationRepository toolInvocationRepository;

    @Autowired
    private TestEntityManager entityManager;

    private JpaAgentRunAuditor auditor;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        auditor = new JpaAgentRunAuditor(repository);
    }

    private void flushAndDetach() {
        entityManager.flush();
        entityManager.clear();
    }

    private void recordStarted(String runId, String sessionId, String userMessage) {
        auditor.recordStarted(runId, sessionId, "ops-agent", "cli", "u-1", userMessage,
                "deepseek", "deepseek-chat");
    }

    @Test
    void 手工脚本建表_开始阶段就记下问的是什么与用的哪个模型() {
        recordStarted("run-1", "s-1", "北京今天天气怎么样？");
        flushAndDetach();

        AgentRunEntity loaded = repository.findById("run-1").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(JpaAgentRunAuditor.STATUS_RUNNING);
        assertThat(loaded.getUserMessage()).isEqualTo("北京今天天气怎么样？");
        assertThat(loaded.getProvider()).isEqualTo("deepseek");
        assertThat(loaded.getModel()).isEqualTo("deepseek-chat");
        assertThat(loaded.getProfileName()).isEqualTo("ops-agent");
        assertThat(loaded.getChannel()).isEqualTo("cli");
        assertThat(loaded.getUserId()).isEqualTo("u-1");
        // 时间列必须能正确往返（整数亲和性）
        assertThat(loaded.getStartedAt()).isNotNull();
        // 尚未结束：终态字段都应为空，便于后台区分"进行中"与"卡死"
        assertThat(loaded.getStopReason()).isNull();
        assertThat(loaded.getCompletedAt()).isNull();
    }

    @Test
    void 成功收尾_终态为FINISHED且标记成功() {
        recordStarted("run-2", "s-2", "帮我查一下天气并给穿搭建议");
        auditor.recordFinished("run-2", StopReason.FINAL_ANSWER, 2, 1,
                new TokenUsage(120, 40, 160), null);
        flushAndDetach();

        AgentRunEntity loaded = repository.findById("run-2").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(JpaAgentRunAuditor.STATUS_FINISHED);
        assertThat(loaded.getStopReason()).isEqualTo("FINAL_ANSWER");
        assertThat(loaded.getSuccess()).isTrue();
        assertThat(loaded.getIterations()).isEqualTo(2);
        assertThat(loaded.getToolCallCount()).isEqualTo(1);
        assertThat(loaded.getPromptTokens()).isEqualTo(120);
        assertThat(loaded.getCompletionTokens()).isEqualTo(40);
        assertThat(loaded.getTotalTokens()).isEqualTo(160);
        assertThat(loaded.getErrorMessage()).isNull();
        assertThat(loaded.getCompletedAt()).isNotNull();
    }

    @Test
    void 失败收尾_留下停止原因与错误原因并标记失败() {
        recordStarted("run-3", "s-3", "查天气");
        auditor.recordFinished("run-3", StopReason.TIMEOUT, 3, 2,
                new TokenUsage(200, 60, 260), "单次处理超过整体耗时上限（60 秒），已中止");
        flushAndDetach();

        AgentRunEntity loaded = repository.findById("run-3").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(JpaAgentRunAuditor.STATUS_FINISHED);
        assertThat(loaded.getStopReason()).isEqualTo("TIMEOUT");
        assertThat(loaded.getSuccess()).isFalse();
        // 失败也要留下已完成的进度，而不是只有一句错误
        assertThat(loaded.getIterations()).isEqualTo(3);
        assertThat(loaded.getToolCallCount()).isEqualTo(2);
        assertThat(loaded.getTotalTokens()).isEqualTo(260);
        assertThat(loaded.getErrorMessage()).contains("60");
    }

    @Test
    void 缺少开始记录时_收尾仍会补写一条而不丢记录() {
        auditor.recordFinished("run-orphan", StopReason.MODEL_ERROR, 1, 0, null, "connect timeout");
        flushAndDetach();

        AgentRunEntity loaded = repository.findById("run-orphan").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(JpaAgentRunAuditor.STATUS_FINISHED);
        assertThat(loaded.getSuccess()).isFalse();
        assertThat(loaded.getErrorMessage()).isEqualTo("connect timeout");
    }

    @Test
    void 明细审计_自动取当前处理的runId并可按runId查回() {
        // 明细审计（llm_calls / tool_invocations）从"当前处理"上下文取 runId，
        // 因此后台能从一个请求下钻到它的每一次模型调用与工具调用。
        // 这里不需要 Profile，只验证 runId 的传递与落库。
        AgentContext.set(null, "run-ctx");
        try {
            new JpaLlmCallAuditor(llmCallRepository)
                    .record("s-1", "deepseek", "deepseek-chat", new TokenUsage(10, 5, 15), true, null, 12L);
            new JpaToolInvocationAuditor(toolInvocationRepository)
                    .record("s-1", "ops-agent", "http_get", "{\"url\":\"http://x\"}",
                            true, null, "晴天 25℃", 30L);
        } finally {
            AgentContext.clear();
        }
        flushAndDetach();

        assertThat(llmCallRepository.findByRunId("run-ctx")).hasSize(1);
        assertThat(llmCallRepository.findByRunId("run-ctx").get(0).getSessionId()).isEqualTo("s-1");
        assertThat(toolInvocationRepository.findByRunId("run-ctx")).hasSize(1);
        assertThat(toolInvocationRepository.findByRunId("run-ctx").get(0).getToolName()).isEqualTo("http_get");
    }

    @Test
    void 不在处理上下文中调用_明细照常写入且runId为空() {
        new JpaLlmCallAuditor(llmCallRepository)
                .record("s-9", "deepseek", "deepseek-chat", null, false, "connect timeout", 5L);
        flushAndDetach();

        List<io.oryxos.storage.entity.LlmCallEntity> calls = llmCallRepository.findBySessionId("s-9");
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getRunId()).isNull();
        assertThat(calls.get(0).getSuccess()).isFalse();
        assertThat(calls.get(0).getErrorMessage()).isEqualTo("connect timeout");
    }

    @Test
    void 同一会话的多次请求_可按开始时间倒序查回() {
        recordStarted("run-a", "s-9", "第一次提问");
        auditor.recordFinished("run-a", StopReason.FINAL_ANSWER, 1, 0, new TokenUsage(1, 1, 2), null);
        AgentRunEntity first = repository.findById("run-a").orElseThrow();
        first.setStartedAt(Instant.now().minusSeconds(60));
        repository.save(first);

        recordStarted("run-b", "s-9", "第二次提问");
        auditor.recordFinished("run-b", StopReason.FINAL_ANSWER, 1, 0, new TokenUsage(1, 1, 2), null);
        flushAndDetach();

        List<AgentRunEntity> runs = repository.findBySessionIdOrderByStartedAtDesc("s-9");
        assertThat(runs).hasSize(2);
        assertThat(runs.get(0).getRunId()).isEqualTo("run-b");
        assertThat(runs.get(0).getUserMessage()).isEqualTo("第二次提问");
        assertThat(repository.findTop50ByOrderByStartedAtDesc()).hasSizeGreaterThanOrEqualTo(2);
    }
}
