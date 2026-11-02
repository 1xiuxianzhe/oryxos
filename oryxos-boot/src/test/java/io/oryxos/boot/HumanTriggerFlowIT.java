package io.oryxos.boot;

import io.oryxos.boot.support.StubOpenAiServer;
import io.oryxos.boot.support.StubOpenAiServer.ToolCall;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.storage.entity.AgentRunEntity;
import io.oryxos.storage.entity.LlmCallEntity;
import io.oryxos.storage.entity.ToolInvocationEntity;
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

/**
 * 人推链路的端到端对账（27 节 §三、§五）。
 *
 * <p>验的不是"某个类的方法被调用了"，而是**一次对话在库里留下的账目自洽**：</p>
 * <pre>
 * 用户提问 ──► 第 1 轮模型（要工具） ──► 执行 2 个 read_file（1 成功 / 1 被沙箱拦）
 *          └─► 第 2 轮模型（给答案）
 *
 * 期望：agent_runs 1 行(FINAL_ANSWER, iterations=2, toolCallCount=2)
 *       llm_calls 2 行(都 success，且都带 runId)
 *       tool_invocations 2 行(1 success / 1 fail —— 失败也必须留痕)
 * </pre>
 *
 * <p>模型用 {@link StubOpenAiServer} 顶替，其余每一站都是真的：Spring AI 适配、Provider 审计、
 * ReAct 循环、ToolExecutor、沙箱、Session 持久化、SQLite。这样不吃密钥、不连外网，
 * 可以跟单元测试一起跑。</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HumanTriggerFlowIT {

    /** 测试工作区：Profile、被读的文件、SQLite 都在这里，互不污染。 */
    private static final Path WORKSPACE = prepareWorkspace();

    private static final StubOpenAiServer STUB = StubOpenAiServer.start(
            List.of(
                    new ToolCall("read_file", "{\"path\":\"" + helloPath() + "\"}"),
                    // 越界路径：先撞沙箱，读不到任何东西——验证"拒绝时 IO 一行都不执行"
                    new ToolCall("read_file", "{\"path\":\"C:/Windows/win.ini\"}")),
            "HELLO.txt 里写的是 hello oryxos。");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("oryxos.workspace", WORKSPACE::toString);
        registry.add("oryxos.providers[0].name", () -> "stub");
        registry.add("oryxos.providers[0].api-key", () -> "stub-key");
        registry.add("oryxos.providers[0].base-url", STUB::baseUrl);
        registry.add("oryxos.sandbox.enabled", () -> "true");
        registry.add("oryxos.sandbox.allowed-paths[0]", WORKSPACE::toString);
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + WORKSPACE.resolve("flow.db"));
        registry.add("logging.level.io.oryxos", () -> "INFO");
    }

    @Autowired
    private AgentService agentService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private LlmCallRepository llmCallRepository;

    @Autowired
    private ToolInvocationRepository toolInvocationRepository;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @BeforeEach
    void resetStub() {
        // 每个用例都从"模型第一轮要工具"开始，避免方法执行顺序影响桩的节奏
        STUB.reset();
    }

    @Test
    void 一次带工具的对话_三张审计表账目自洽() {
        Session session = sessionManager.getOrCreate("it", "human", "smoke");

        String reply = agentService.process(session, "HELLO.txt 里写了什么？");
        String sessionId = session.getSessionId();

        // ① 对外答复
        assertThat(reply).contains("hello oryxos");
        // ② 轮数：要工具一次 + 给答案一次
        assertThat(STUB.chatRequests()).isEqualTo(2);

        // ③ llm_calls：两轮都成功，且都挂得上 runId（否则会话明细页无法按提问聚合）
        List<LlmCallEntity> llmCalls = llmCallRepository.findBySessionId(sessionId);
        assertThat(llmCalls).hasSize(2);
        assertThat(llmCalls).allMatch(call -> Boolean.TRUE.equals(call.getSuccess()));
        assertThat(llmCalls).allMatch(call -> call.getRunId() != null);
        assertThat(llmCalls).allMatch(call -> call.getTotalTokens() != null && call.getTotalTokens() > 0);

        // ④ tool_invocations：一次成功、一次被沙箱拒绝，失败的也要留痕
        List<ToolInvocationEntity> tools = toolInvocationRepository.findBySessionId(sessionId);
        assertThat(tools).hasSize(2);
        assertThat(tools).allMatch(inv -> "read_file".equals(inv.getToolName()));

        ToolInvocationEntity allowed = tools.stream()
                .filter(inv -> Boolean.TRUE.equals(inv.getSuccess()))
                .findFirst()
                .orElseThrow();
        assertThat(allowed.getResult()).contains("hello oryxos");

        ToolInvocationEntity blocked = tools.stream()
                .filter(inv -> !Boolean.TRUE.equals(inv.getSuccess()))
                .findFirst()
                .orElseThrow();
        assertThat(blocked.getErrorMessage()).contains("白名单");

        // ⑤ agent_runs：一次提问一行，结局与计数对得上
        List<AgentRunEntity> runs =
                agentRunRepository.findBySessionIdOrderByStartedAtDesc(sessionId);
        assertThat(runs).hasSize(1);
        AgentRunEntity run = runs.get(0);
        assertThat(run.getStatus()).isEqualTo("FINISHED");
        assertThat(run.getStopReason()).isEqualTo("FINAL_ANSWER");
        assertThat(run.getIterations()).isEqualTo(2);
        assertThat(run.getToolCallCount()).isEqualTo(2);
        assertThat(run.getTotalTokens()).isNotNull().isPositive();
    }

    @Test
    void 同一会话第二次提问_同一份历史里继续记账() {
        Session session = sessionManager.getOrCreate("it", "human-second", "smoke");
        String sessionId = session.getSessionId();

        agentService.process(session, "第一次提问");
        int afterFirst = llmCallRepository.findBySessionId(sessionId).size();

        STUB.reset();   // 新的一次提问，桩重新从"要工具"开始
        agentService.process(session, "第二次提问");

        // 会话历史累积：两次提问各自成 run，账目不覆盖
        assertThat(agentRunRepository.findBySessionIdOrderByStartedAtDesc(sessionId)).hasSize(2);
        assertThat(llmCallRepository.findBySessionId(sessionId)).hasSize(afterFirst * 2);
        assertThat(session.getMessages()).hasSizeGreaterThan(2 * 2);
    }

    @Test
    void 模型5xx由本层显式重试_每次尝试都进审计() {
        Session session = sessionManager.getOrCreate("it", "retry", "smoke");
        String sessionId = session.getSessionId();

        STUB.failNextChatWithServerError();

        String reply = agentService.process(session, "HELLO.txt 里写了什么？");
        assertThat(reply).contains("hello oryxos");

        // 3 次真实请求：失败的那次 + 重试成功的那次（要工具）+ 最终答复。
        // 真正有区分力的是下面的 llm_calls 行数——失败那次如果被 Spring AI 的重试模板吞在内部，
        // 这里只会剩 2 行，账目就与真实请求次数对不上了。
        assertThat(STUB.chatRequests()).isEqualTo(3);

        List<LlmCallEntity> calls = llmCallRepository.findBySessionId(sessionId);
        assertThat(calls).hasSize(3);
        // 失败的中间尝试也占一行，且带错误原因——账目行数 = 真实请求次数
        assertThat(calls).filteredOn(call -> !Boolean.TRUE.equals(call.getSuccess()))
                .hasSize(1)
                .allMatch(call -> call.getErrorMessage() != null);
        assertThat(calls).filteredOn(call -> Boolean.TRUE.equals(call.getSuccess())).hasSize(2);
    }

    // ---------- 工作区 ----------

    private static Path prepareWorkspace() {
        try {
            Path dir = Files.createTempDirectory("oryxos-human-flow-");
            Files.createDirectories(dir.resolve("profiles"));
            Files.writeString(dir.resolve("profiles/smoke.yaml"), """
                    description: 全链路串联验收用 Agent
                    identity: 读文件的助手
                    provider:
                      name: stub
                      model: stub-model
                    tools:
                      - name: read_file
                    settings:
                      max_iterations: 5
                      max_history_turns: 10
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
