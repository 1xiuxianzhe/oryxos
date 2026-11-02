package io.oryxos;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileLoader;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实模型 + 自实现循环的端到端冒烟（Demo 一：多步任务一次办成）。
 *
 * <p>注册一个<b>本地假工具</b>（返回固定天气），使验证不依赖任何外部 HTTP 与域名白名单，
 * 但仍然由真实模型决定"是否调用工具"。断言的关键是：</p>
 * <ol>
 *   <li>模型确实请求了工具，且工具被 {@code ToolExecutor} 执行了；</li>
 *   <li>会话里同时留下 assistant（含工具请求）与 tool（工具结果）消息；</li>
 *   <li>最终答复不再包含工具请求。</li>
 * </ol>
 *
 * <p>需要真实密钥，默认跳过：{@code set DEEPSEEK_API_KEY=xxx} 后手动运行。</p>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/oryxos-react-loop-smoke.db",
        "oryxos.workspace=../.oryxos"
})
class ReactLoopSmokeIT {

    private static final AtomicInteger WEATHER_CALLS = new AtomicInteger();

    @Autowired
    private AgentService agentService;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private ProfileLoader profileLoader;

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void 真实模型驱动循环_调用工具后给出建议() {
        toolRegistry.register(fakeWeatherTool());

        Profile profile = profileLoader.get("default");
        assertThat(profile).as("Profile [default] 未加载，请检查 .oryxos/profiles/default.yaml").isNotNull();

        Session session = sessionManager.create("smoke-session", "default", "cli", "tester");
        String reply = agentService.process(session.getSessionId(), "default", "cli", "tester",
                "北京今天天气怎么样？我该穿什么？");

        System.out.println("========== ReAct Smoke ==========");
        System.out.println("reply = " + reply);
        System.out.println("天气工具被调用次数 = " + WEATHER_CALLS.get());
        System.out.println("会话消息数 = " + session.getMessages().size());
        System.out.println("=================================");

        assertThat(reply).isNotBlank();
        assertThat(WEATHER_CALLS.get()).as("模型应当请求调用天气工具").isGreaterThan(0);

        boolean hasToolRequest = session.getMessages().stream()
                .anyMatch(m -> "assistant".equals(m.getRole()) && m.getContent().contains("[Tool Request]"));
        boolean hasToolResult = session.getMessages().stream()
                .anyMatch(m -> "tool".equals(m.getRole()) && m.getContent().contains("晴天"));
        assertThat(hasToolRequest).as("会话中应留下工具请求").isTrue();
        assertThat(hasToolResult).as("会话中应留下工具结果").isTrue();
        assertThat(reply).as("最终答复不应是工具请求本身").doesNotContain("[Tool Request]");
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
