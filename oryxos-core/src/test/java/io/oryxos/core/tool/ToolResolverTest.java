package io.oryxos.core.tool;

import io.oryxos.core.profile.Profile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具授权契约：按 Agent 过滤、不多不少、不跨 Agent 污染。
 *
 * <p>见 {@code specs/005-tool-system/contracts/tool-authorization.md} 第 6 节。</p>
 */
class ToolResolverTest {

    private ToolRegistry registry;
    private ToolResolver resolver;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        List.of("read_file", "write_file", "list_dir", "shell", "http_get", "http_post", "notify")
                .forEach(name -> registry.register(tool(name)));
        resolver = new ToolResolver(registry);
    }

    private static OryxTool tool(String name) {
        return new OryxTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return "描述-" + name;
            }

            @Override
            public String getInputSchema() {
                return "{}";
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                return ToolResult.ok("ok");
            }
        };
    }

    private static Profile agent(String name, String... declaredTools) {
        Profile profile = new Profile();
        profile.setName(name);
        if (declaredTools != null) {
            profile.setTools(Arrays.stream(declaredTools)
                    .map(toolName -> {
                        Profile.ToolConfig config = new Profile.ToolConfig();
                        config.setName(toolName);
                        return config;
                    })
                    .toList());
        }
        return profile;
    }

    private static List<String> names(List<OryxTool> tools) {
        return tools.stream().map(OryxTool::getName).sorted().toList();
    }

    @Test
    void 声明了三个工具则恰好暴露这三个() {
        List<OryxTool> resolved = resolver.resolve(agent("ops", "read_file", "list_dir", "shell"));

        // 断言"相等"而不是"包含"：多一个（授权过宽）与少一个（授权过窄）都是错
        assertThat(names(resolved)).containsExactly("list_dir", "read_file", "shell");
    }

    @Test
    void 未声明工具清单时不限制() {
        List<OryxTool> resolved = resolver.resolve(agent("default", (String[]) null));

        assertThat(resolved).hasSize(registry.listAll().size());
    }

    @Test
    void 声明为空列表时不限制() {
        List<OryxTool> resolved = resolver.resolve(agent("default"));

        assertThat(resolved).hasSize(registry.listAll().size());
    }

    @Test
    void 声明了不存在的工具名_被排除且不回退成全量() {
        List<OryxTool> resolved = resolver.resolve(agent("ops", "read_file", "ghost_tool"));

        assertThat(names(resolved)).containsExactly("read_file");
        assertThat(resolved).hasSizeLessThan(registry.listAll().size());
    }

    @Test
    void 声明的工具全部不存在时返回空集合而不是全量() {
        List<OryxTool> resolved = resolver.resolve(agent("ops", "ghost_a", "ghost_b"));

        assertThat(resolved).isEmpty();
    }

    @Test
    void 两个Agent交替组装_各自与自己声明一致且互不污染() {
        Profile ops = agent("ops", "shell");
        Profile assistant = agent("assistant", "http_get", "http_post");

        assertThat(names(resolver.resolve(ops))).containsExactly("shell");
        assertThat(names(resolver.resolve(assistant))).containsExactly("http_get", "http_post");
        // 再回头组装一次，确认没有被上一次结果污染
        assertThat(names(resolver.resolve(ops))).containsExactly("shell");
    }

    @Test
    void 过滤不区分工具来源_外部工具同样受授权约束() {
        registry.register(tool("weather_forecast"));

        // 显式声明时只暴露声明的那批（含外部工具）
        assertThat(names(resolver.resolve(agent("ops", "read_file", "weather_forecast"))))
                .containsExactly("read_file", "weather_forecast");

        // 未声明时外部工具也一并出现
        assertThat(names(resolver.resolve(agent("any")))).contains("weather_forecast");
    }

    @Test
    void 重复声明同一个工具不会重复暴露() {
        List<OryxTool> resolved = resolver.resolve(agent("ops", "shell", "shell"));

        assertThat(names(resolved)).containsExactly("shell");
    }
}
