package io.oryxos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把一个外部工具服务的工具包装成与内置工具**完全同形态**的工具。
 *
 * <p>包装之后使用方（含 Agent）无法也不应区分来源——这正是"统一工具抽象"要买到的东西：
 * 新增一档渠道只新增实现类，不改接口、不改调用方。</p>
 *
 * <p>三处类型转换需要注意：</p>
 * <ul>
 *   <li>参数 schema 在仓库里的统一形态是 <b>JSON 文本</b>，而外部服务给的是结构化对象，
 *       因此要序列化一次；</li>
 *   <li>调用入参在仓库里是字段到值的映射，正好与外部服务的调用请求一致，<b>原样转发</b>；</li>
 *   <li>返回内容是内容块列表，拼接为文本；失败一律包装为<b>可重试</b>的失败结果——
 *       外部服务是另一个进程或另一台机器，它的失败多半是瞬态的。</li>
 * </ul>
 */
public class McpToolAdapter implements OryxTool {

    private static final Logger logger = LoggerFactory.getLogger(McpToolAdapter.class);

    /** schema 序列化失败时的兜底：至少让模型知道"这是个对象参数"。 */
    private static final String FALLBACK_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final McpSyncClient client;
    private final McpSchema.Tool tool;
    private final String inputSchemaJson;

    public McpToolAdapter(McpSyncClient client, McpSchema.Tool tool) {
        this.client = client;
        this.tool = tool;
        this.inputSchemaJson = serializeSchema(tool.inputSchema());
    }

    @Override
    public String getName() {
        return tool.name();
    }

    @Override
    public String getDescription() {
        String description = tool.description();
        return description == null || description.isBlank() ? tool.name() : description;
    }

    @Override
    public String getInputSchema() {
        return inputSchemaJson;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        Map<String, Object> arguments = input == null ? Map.of() : input;
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(tool.name(), arguments));
        } catch (RuntimeException e) {
            // 服务中途挂掉、超时、协议错误：对上游来说都是"这次没成，可以再试"
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            logger.warn("[Mcp] 工具调用失败: tool={}, error={}", tool.name(), reason);
            return ToolResult.fail("外部工具调用失败（" + reason + "）", true);
        }

        String text = flatten(result.content());
        if (Boolean.TRUE.equals(result.isError())) {
            return ToolResult.fail("外部工具执行失败: " + text, true);
        }
        return ToolResult.ok(text);
    }

    /** 结构化 schema → JSON 文本（统一形态要求）。 */
    private static String serializeSchema(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return FALLBACK_SCHEMA;
        }
        try {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("type", schema.type() == null ? "object" : schema.type());
            normalized.put("properties", schema.properties() == null ? Map.of() : schema.properties());
            normalized.put("required", schema.required() == null ? List.of() : schema.required());
            if (schema.additionalProperties() != null) {
                normalized.put("additionalProperties", schema.additionalProperties());
            }
            return OBJECT_MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            logger.warn("[Mcp] 工具参数 schema 序列化失败，回退为通用对象 schema: tool={}", e.getMessage());
            return FALLBACK_SCHEMA;
        }
    }

    /** 内容块拼成文本；非文本块退回其字符串形式，避免信息整个丢掉。 */
    private static String flatten(List<McpSchema.Content> contents) {
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        return contents.stream()
                .map(content -> content instanceof McpSchema.TextContent text
                        ? text.text()
                        : String.valueOf(content))
                .collect(Collectors.joining("\n"));
    }
}
