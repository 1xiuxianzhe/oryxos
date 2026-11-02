package io.oryxos.core.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注册表契约：重名拒绝与存在性判断。
 *
 * <p>见 {@code specs/005-tool-system/contracts/tool-registry.md} 第 2 节。</p>
 */
class ToolRegistryTest {

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

    @Test
    void 重名注册被拒绝_先注册者保留() {
        ToolRegistry registry = new ToolRegistry();
        OryxTool first = tool("shell");
        OryxTool second = tool("shell");

        registry.register(first);
        registry.register(second);

        assertThat(registry.get("shell")).isSameAs(first);
        assertThat(registry.listAll()).hasSize(1);
    }

    @Test
    void 内置工具与外部工具撞名时内置胜出() {
        ToolRegistry registry = new ToolRegistry();
        OryxTool builtin = tool("http_get");
        OryxTool external = tool("http_get");

        // 契约规定的注册顺序：内置先，外部后
        registry.register(builtin);
        registry.register(external);

        assertThat(registry.get("http_get")).isSameAs(builtin);
    }

    @Test
    void contains对已登记与未登记分别返回真假() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file"));

        assertThat(registry.contains("read_file")).isTrue();
        assertThat(registry.contains("not_registered")).isFalse();
    }

    @Test
    void get对不存在的名字返回空而不抛异常() {
        ToolRegistry registry = new ToolRegistry();

        assertThat(registry.get("ghost")).isNull();
    }
}
