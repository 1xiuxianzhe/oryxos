package io.oryxos.core.react;

import io.oryxos.core.audit.ToolInvocationAuditor;
import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolInvocationValidator;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ToolExecutor 验收测试：执行顺序契约、错误降级（不吞异常）、审计成功/失败都写、审计失败不阻断。
 */
class ToolExecutorTest {

    private ToolRegistry registry;
    private ToolInvocationAuditor auditor;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        auditor = mock(ToolInvocationAuditor.class);
        executor = new ToolExecutor(registry, null, auditor);
    }

    private OryxTool okTool(String name) {
        return new OryxTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name + " 描述";
            }

            @Override
            public String getInputSchema() {
                return "{\"type\":\"object\",\"properties\":{}}";
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                return ToolResult.ok("执行成功:" + input);
            }
        };
    }

    private OryxTool throwingTool(String name) {
        return new OryxTool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name + " 描述";
            }

            @Override
            public String getInputSchema() {
                return "{\"type\":\"object\",\"properties\":{}}";
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                throw new IllegalStateException("工具内部炸了");
            }
        };
    }

    @Test
    void 成功调用_写入success为true的审计() {
        registry.register(okTool("http_get"));

        ToolResult result = executor.execute("s-1", new LlmClient.ToolCall("http_get", "{\"url\":\"http://x\"}"));

        assertThat(result.isSuccess()).isTrue();
        verify(auditor).record(eq("s-1"), isNull(), eq("http_get"), anyString(),
                eq(true), isNull(), anyString(), anyLong());
    }

    @Test
    void 工具未注册_返回失败结果且不抛异常() {
        ToolResult result = executor.execute("s-1", new LlmClient.ToolCall("ghost_tool", "{}"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("未找到工具");
        verify(auditor).record(eq("s-1"), isNull(), eq("ghost_tool"), anyString(),
                eq(false), anyString(), isNull(), anyLong());
    }

    @Test
    void 参数不是合法JSON_降级为原样参数_不崩溃() {
        registry.register(okTool("http_get"));

        ToolResult result = executor.execute("s-1", new LlmClient.ToolCall("http_get", "这不是JSON"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("raw");
    }

    @Test
    void 工具内部异常_转为失败结果且不向上抛() {
        registry.register(throwingTool("http_get"));

        assertThatCode(() -> {
            ToolResult result = executor.execute("s-1", new LlmClient.ToolCall("http_get", "{}"));
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getError()).contains("工具内部炸了");
        }).doesNotThrowAnyException();

        verify(auditor).record(eq("s-1"), isNull(), eq("http_get"), anyString(),
                eq(false), eq("工具内部炸了"), isNull(), anyLong());
    }

    @Test
    void 前置校验不通过_返回失败结果并写审计() {
        registry.register(okTool("http_get"));
        ToolInvocationValidator validator = (tool, input) -> {
            throw new SecurityException("域名不在白名单内");
        };
        ToolExecutor guarded = new ToolExecutor(registry, validator, auditor);

        ToolResult result = guarded.execute("s-1", new LlmClient.ToolCall("http_get", "{}"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("前置校验未通过").contains("域名不在白名单内");
        verify(auditor).record(eq("s-1"), isNull(), eq("http_get"), anyString(),
                eq(false), anyString(), isNull(), anyLong());
    }

    @Test
    void 审计写入失败_不阻断主流程() {
        registry.register(okTool("http_get"));
        doThrow(new RuntimeException("审计库挂了"))
                .when(auditor).record(anyString(), any(), anyString(), any(), anyBoolean(),
                        any(), any(), anyLong());

        ToolResult result = executor.execute("s-1", new LlmClient.ToolCall("http_get", "{}"));

        // 审计失败绝不能把工具结果吞掉（FR-020）
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void 多工具调用_按顺序依次执行() {
        registry.register(okTool("first"));
        registry.register(okTool("second"));

        List<ToolResult> results = executor.executeAll("s-1", List.of(
                new LlmClient.ToolCall("first", "{}"),
                new LlmClient.ToolCall("second", "{}")));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getContent()).contains("执行成功");
        verify(auditor, times(2)).record(eq("s-1"), any(), anyString(), anyString(),
                anyBoolean(), any(), any(), anyLong());
    }

    @Test
    void 空工具名_返回失败结果() {
        ToolResult result = executor.execute("s-1", new LlmClient.ToolCall("  ", "{}"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("工具名为空");
    }
}
