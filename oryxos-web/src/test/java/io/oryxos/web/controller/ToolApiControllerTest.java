package io.oryxos.web.controller;

import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工具清单端点契约：内容来自真实注册表，不是硬编码清单。
 *
 * <p>见 {@code specs/005-tool-system/contracts/tool-registry.md} 验证项 6。</p>
 */
class ToolApiControllerTest {

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

    private static MockMvc mockMvc(ToolRegistry registry) {
        return MockMvcBuilders.standaloneSetup(new ToolApiController(registry)).build();
    }

    @Test
    void 返回注册表中的真实工具() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file"));
        registry.register(tool("notify"));

        mockMvc(registry).perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("notify"))
                .andExpect(jsonPath("$[1].name").value("read_file"))
                .andExpect(jsonPath("$[0].description").value("描述-notify"));
    }

    @Test
    void 含外部工具时同样反映注册表内容() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file"));
        registry.register(tool("weather_forecast"));

        mockMvc(registry).perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].name").value("weather_forecast"));
    }

    @Test
    void 注册表为空时返回空数组而不是硬编码清单() throws Exception {
        mockMvc(new ToolRegistry()).perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void 重名工具只出现一次() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file"));
        registry.register(tool("read_file"));

        mockMvc(registry).perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }
}
