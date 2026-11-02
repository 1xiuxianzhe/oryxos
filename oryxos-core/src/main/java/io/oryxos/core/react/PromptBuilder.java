package io.oryxos.core.react;

import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.llm.LlmRequest;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.session.Session;
import io.oryxos.core.tool.OryxTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PromptBuilder —— 为每轮 ReAct 循环组装模型输入。
 *
 * <p>四部分固定顺序（FR-011）：</p>
 * <ol>
 *   <li>角色与启动设定：identity + Bootstrap + SKILL，末尾附当前日期时间；</li>
 *   <li>长期记忆（跨会话；为空则不产生消息）；</li>
 *   <li>最近 N 次<b>完整往返</b>的会话历史（超出截断）；</li>
 *   <li>当前可用工具 schema（随请求单独传递，不占消息位）。</li>
 * </ol>
 *
 * <p>"长期记忆"与"会话历史"是两码事：前者跨会话都在，后者只是本次对话的往来记录。</p>
 */
public class PromptBuilder {

    private static final Logger logger = LoggerFactory.getLogger(PromptBuilder.class);

    /** 当前时间格式：模型自己不知道"今天"是几号，定时场景全靠这一行。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    private final ContextLoader contextLoader;
    private final MemoryService memoryService;

    public PromptBuilder(ContextLoader contextLoader, MemoryService memoryService) {
        this.contextLoader = contextLoader;
        this.memoryService = memoryService;
    }

    /** 组装一轮完整的模型输入。 */
    public LlmRequest build(Session session, Profile profile, List<OryxTool> tools) {
        return new LlmRequest(
                buildSystemPrompt(profile),
                recallMemory(session),
                buildHistory(session, profile.maxHistoryTurns()),
                buildToolDescriptions(tools));
    }

    /**
     * 第一部分：identity + Bootstrap + SKILL，末尾附当前日期时间。
     */
    public String buildSystemPrompt(Profile profile) {
        StringBuilder sb = new StringBuilder();
        if (contextLoader != null) {
            String context = contextLoader.loadSystemContext(profile);
            if (context != null && !context.isBlank()) {
                sb.append(context.trim());
            }
        }
        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append("当前时间：").append(ZonedDateTime.now().format(TIMESTAMP_FORMAT));
        return sb.toString();
    }

    /**
     * 第二部分：长期记忆——核心记忆始终在场，归档记忆按阈值截断。
     *
     * <p>注入的是<b>整份长期记忆</b>，不是"按最后一句话召回的结果"：跨会话记得住谁是谁，
     * 靠的是每次都在场，而不是碰巧与上一句话有相同的词。要不要按关键词翻旧事，
     * 由 Agent 显式调 {@code recall_memory} 决定。</p>
     *
     * <p>记忆只是上下文的一部分：门面缺失或记忆为空时返回空串，不产生消息，也不影响其余三段。</p>
     */
    public String recallMemory(Session session) {
        if (memoryService == null) {
            return "";
        }
        String memory = memoryService.buildContext(session);
        return memory == null ? "" : memory;
    }

    /**
     * 第三部分：最近 N 次完整往返。
     *
     * <p>切分规则（research.md D-04）：以 {@code role=user} 为一段往返的起点；
     * 取最近 maxTurns 段；最后一段即使尚未闭合（当前正在处理的那次）也计入。
     * 这样工具消息天然留在其所属往返内，不会被拦腰截断。</p>
     */
    public List<Map<String, String>> buildHistory(Session session, int maxTurns) {
        List<Session.Message> messages = session.getMessages();
        List<Map<String, String>> history = new ArrayList<>();
        if (messages.isEmpty()) {
            return history;
        }

        List<Integer> turnStarts = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if ("user".equals(messages.get(i).getRole())) {
                turnStarts.add(i);
            }
        }

        int from = 0;
        if (turnStarts.size() > maxTurns) {
            from = turnStarts.get(turnStarts.size() - maxTurns);
            logger.debug("会话历史截断: sessionId={}, 总往返={}, 保留={}, 丢弃消息数={}",
                    session.getSessionId(), turnStarts.size(), maxTurns, from);
        }

        for (int i = from; i < messages.size(); i++) {
            Session.Message message = messages.get(i);
            history.add(Map.of(
                    "role", message.getRole() == null ? "user" : message.getRole(),
                    "content", message.getContent() == null ? "" : message.getContent()));
        }
        return history;
    }

    /**
     * 第四部分：把已注册工具转成 LLM 工具描述（只描述，不执行）。
     */
    public List<LlmClient.ToolDescription> buildToolDescriptions(List<OryxTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
                .map(tool -> new LlmClient.ToolDescription(
                        tool.getName(), tool.getDescription(), tool.getInputSchema()))
                .toList();
    }
}
