package io.oryxos;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileLoader;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResult;
import io.oryxos.storage.entity.LlmCallEntity;
import io.oryxos.storage.entity.ToolInvocationEntity;
import io.oryxos.storage.repository.LlmCallRepository;
import io.oryxos.storage.repository.ToolInvocationRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实模型驱动一次完整 ReAct 循环，并把审计**真实写进应用库** {@code .oryxos/oryxos.db}。
 *
 * <p>与 {@link ReactLoopSmokeIT} 的区别：这里<b>不覆盖</b>数据源，用的就是
 * {@code application.yaml} 里那套配置（同一份 schema.sql、同一批 {@code Jpa*Auditor}），
 * 因此跑完之后你在应用库里就能直接查到本次调用的 {@code llm_calls} 与 {@code tool_invocations}。</p>
 *
 * <p>库里落在哪：默认从当前目录向上找到含 {@code .oryxos} 的那一层（即仓库根）。
 * 可用 {@code -Doryxos.realrun.workspace=D:/xxx/.oryxos} 指定别处。测试会把真实文件路径打印出来。</p>
 *
 * <p>需要真实密钥，两种运行方式（IDEA 的环境变量是<b>按 Run Configuration</b> 配的）：</p>
 * <pre>
 * # IDEA：直接运行本类，并在该 JUnit 运行配置里设置环境变量 DEEPSEEK_API_KEY
 * # Maven：
 * mvn -pl oryxos-boot -am test -Dtest=ReactLoopRealRunIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReactLoopRealRunIT {

    private static final AtomicInteger WEATHER_CALLS = new AtomicInteger();

    private static final Path WORKSPACE = findWorkspace();

    /** 让测试与"正在运行的应用"共用同一个库：不覆盖 datasource，只把工作区指向仓库根。 */
    @DynamicPropertySource
    static void useAppDatabase(DynamicPropertyRegistry registry) {
        registry.add("oryxos.workspace", () -> WORKSPACE.toString());
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + WORKSPACE.resolve("oryxos.db"));
    }

    /** 从当前目录逐级向上找含 {@code .oryxos} 的目录，保证 Maven / IDEA 两种工作目录都命中同一个库。 */
    private static Path findWorkspace() {
        String override = System.getProperty("oryxos.realrun.workspace");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            Path candidate = current.resolve(".oryxos");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return Path.of("").toAbsolutePath().resolve(".oryxos");
    }

    @Autowired
    private AgentService agentService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private ProfileLoader profileLoader;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private LlmCallRepository llmCallRepository;

    @Autowired
    private ToolInvocationRepository toolInvocationRepository;

    @Test
    void 真实模型跑一轮_审计落进应用库并可查回() {
        // 当前应用启动时没有任何内置工具注册（ToolRegistry 为空），这里手动挂一个本地假工具，
        // 让模型有东西可调，从而同时产出 llm_calls 与 tool_invocations 两类记录。
        toolRegistry.register(fakeWeatherTool());

        Profile profile = profileLoader.get("default");
        assertThat(profile).as("Profile [default] 未加载，请检查 .oryxos/profiles/default.yaml").isNotNull();

        String sessionId = "real-run-" + System.currentTimeMillis();
        Session session = sessionManager.create(sessionId, "default", "cli", "manual-tester");

        System.out.println("========== ReAct 真实运行 ==========");
        System.out.println("数据库文件   = " + WORKSPACE.resolve("oryxos.db"));
        System.out.println("sessionId    = " + sessionId);
        System.out.println("provider     = " + profile.getProvider().getName()
                + " / " + profile.getProvider().getModel());

        String reply = agentService.process(sessionId, "default", "cli", "manual-tester",
                "北京今天天气怎么样？我该穿什么？");

        System.out.println("reply        = " + reply);
        System.out.println("天气工具调用 = " + WEATHER_CALLS.get() + " 次");
        System.out.println("会话消息数   = " + session.getMessages().size());
        System.out.println("------------------------------------");

        assertThat(reply).as("模型答复不能为空").isNotBlank();
        assertThat(WEATHER_CALLS.get()).as("模型应当请求调用天气工具").isGreaterThan(0);

        // 审计一：llm_calls
        List<LlmCallEntity> calls = llmCallRepository.findBySessionId(sessionId);
        calls.forEach(call -> System.out.println("[llm_calls] provider=" + call.getProvider()
                + ", model=" + call.getModel()
                + ", success=" + call.getSuccess()
                + ", tokens=" + call.getTotalTokens()
                + ", latencyMs=" + call.getLatencyMs()
                + ", error=" + call.getErrorMessage()));
        assertThat(calls).as("llm_calls 应记录本次调用").isNotEmpty();
        assertThat(calls).anyMatch(call -> Boolean.TRUE.equals(call.getSuccess()));

        // 审计二：tool_invocations
        List<ToolInvocationEntity> invocations = toolInvocationRepository.findBySessionId(sessionId);
        invocations.forEach(item -> System.out.println("[tool_invocations] tool=" + item.getToolName()
                + ", success=" + item.getSuccess()
                + ", latencyMs=" + item.getLatencyMs()
                + ", result=" + item.getResult()
                + ", error=" + item.getErrorMessage()));
        assertThat(invocations).as("tool_invocations 应记录本次工具调用").isNotEmpty();
        assertThat(invocations).anyMatch(item -> "get_weather".equals(item.getToolName()));
        assertThat(invocations).anyMatch(item -> Boolean.TRUE.equals(item.getSuccess()));
        System.out.println("====================================");
    }

    private OryxTool fakeWeatherTool() {
        return new OryxTool() {
            @Override
            public String getName() {
                return "get_weather";
            }

            @Override
            public String getDescription() {
                return "查询指定城市的当前天气。参数：city（城市名，例如 北京）。";
            }

            @Override
            public String getInputSchema() {
                return "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}";
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                WEATHER_CALLS.incrementAndGet();
                String city = String.valueOf(input.getOrDefault("city", "北京"));
                return ToolResult.ok(city + " 今天晴天，气温 25℃，微风。");
            }
        };
    }
}
