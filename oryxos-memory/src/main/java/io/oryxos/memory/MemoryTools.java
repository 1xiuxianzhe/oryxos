package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MemoryTools —— 归 Memory 能力的两个内置 Tool：{@code save_memory} / {@code recall_memory}。
 *
 * <p>它们是 Agent 与长期记忆交互的<b>唯二通道</b>：Agent 不直接写文件，也不知道文件在哪。
 * 是否值得记、记进核心区还是归档区，由 Agent 自己判断并通过参数说清楚，工具层不猜。</p>
 *
 * <p>实现 {@link OryxTool} 并在装配处显式登记（宪法 III：不用 Spring AI 的注解自动注册与自动执行，
 * 否则工具会被执行两次）。</p>
 */
public final class MemoryTools {

    private MemoryTools() {
    }

    /** 写入记忆。 */
    public static class SaveMemoryTool implements OryxTool {

        private final MemoryService memoryService;

        public SaveMemoryTool(MemoryService memoryService) {
            this.memoryService = memoryService;
        }

        @Override
        public String getName() {
            return "save_memory";
        }

        @Override
        public String getDescription() {
            return "把需要跨会话长期记住的事写进记忆。scope 选 core（用户身份、项目背景、关键约束等"
                    + "每次都要带上的信息）或 archival（其余事实与历史），不确定就填 archival。";
        }

        @Override
        public String getInputSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                    + "\"content\":{\"type\":\"string\",\"description\":\"要记住的内容\"},"
                    + "\"scope\":{\"type\":\"string\",\"enum\":[\"core\",\"archival\"],"
                    + "\"description\":\"写进哪个区块：core 核心记忆（始终在场）或 archival 归档记忆（可检索），缺省 archival\"}"
                    + "},\"required\":[\"content\"]}";
        }

        @Override
        public ToolResult execute(Map<String, Object> input) {
            Object rawContent = input == null ? null : input.get("content");
            String content = rawContent == null ? null : String.valueOf(rawContent);
            if (content == null || content.isBlank()) {
                return ToolResult.fail("缺少 content 参数：要记住的内容不能为空", false);
            }

            MemoryScope scope;
            try {
                scope = parseScope(input.get("scope"));
            } catch (IllegalArgumentException e) {
                return ToolResult.fail(e.getMessage(), false);
            }

            try {
                memoryService.remember(content, scope);
            } catch (RuntimeException e) {
                return ToolResult.fail("写入记忆失败: " + e.getMessage(), true);
            }
            String where = scope == MemoryScope.CORE ? "核心记忆" : "归档记忆";
            return ToolResult.ok("已记住（" + where + "）");
        }
    }

    /** 检索归档记忆。 */
    public static class RecallMemoryTool implements OryxTool {

        private static final String NOT_FOUND = "没有找到相关记忆";

        private final MemoryService memoryService;

        public RecallMemoryTool(MemoryService memoryService) {
            this.memoryService = memoryService;
        }

        @Override
        public String getName() {
            return "recall_memory";
        }

        @Override
        public String getDescription() {
            return "按关键词检索长期记忆的归档区（核心记忆每次都自动带上，不需要检索）。"
                    + "保存之前可以先查一下，避免重复记录。";
        }

        @Override
        public String getInputSchema() {
            return "{\"type\":\"object\",\"properties\":{"
                    + "\"keyword\":{\"type\":\"string\",\"description\":\"检索关键词\"}"
                    + "},\"required\":[\"keyword\"]}";
        }

        @Override
        public ToolResult execute(Map<String, Object> input) {
            Object rawKeyword = input == null ? null : input.get("keyword");
            String keyword = rawKeyword == null ? null : String.valueOf(rawKeyword);
            if (keyword == null || keyword.isBlank()) {
                return ToolResult.fail("缺少 keyword 参数：检索关键词不能为空", false);
            }

            List<String> hits = memoryService.recall(keyword);
            if (hits == null || hits.isEmpty()) {
                return ToolResult.ok(NOT_FOUND);
            }
            return ToolResult.ok(String.join("\n", hits));
        }
    }

    /**
     * 解析 scope 参数：缺省/空白 → 归档记忆（安全默认）；无法识别的取值 → 拒绝。
     *
     * <p>不把无法识别的取值静默改成归档：那会让 Agent 以为写进了核心区而实际没有，
     * 这种"静默改写归属"比一条失败结果更难排查。</p>
     */
    static MemoryScope parseScope(Object raw) {
        if (raw == null) {
            return MemoryScope.ARCHIVAL;
        }
        String value = String.valueOf(raw).strip();
        if (value.isEmpty()) {
            return MemoryScope.ARCHIVAL;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "core" -> MemoryScope.CORE;
            case "archival" -> MemoryScope.ARCHIVAL;
            default -> throw new IllegalArgumentException(
                    "scope 取值非法: " + value + "，合法取值为 core 或 archival");
        };
    }
}
