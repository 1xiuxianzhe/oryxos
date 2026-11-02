package io.oryxos.core.react;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.audit.NoopToolInvocationAuditor;
import io.oryxos.core.audit.ToolInvocationAuditor;
import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.tool.NoopToolInvocationValidator;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolInvocationValidator;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ToolExecutor —— Tool 的实际调度与执行。
 *
 * <p>固定顺序：<b>查工具表 → 前置校验 → 解析参数 → 执行 → 写审计</b>。</p>
 *
 * <p>执行权只在这一个地方发生——这也是能力一为什么要关掉 Spring AI 自动执行的原因：
 * 执行权必须收在这里，不能有第二条路。</p>
 *
 * <p>错误策略：工具未注册、参数非法、前置校验不通过、工具内部抛异常，一律降级为
 * <b>失败结果</b>返回，不向上抛——失败结果会被回填进会话，让模型有机会换个思路继续。</p>
 */
public class ToolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry toolRegistry;
    private final ToolInvocationValidator validator;
    private final ToolInvocationAuditor auditor;
    private final ObjectMapper objectMapper;

    public ToolExecutor(ToolRegistry toolRegistry,
                        ToolInvocationValidator validator,
                        ToolInvocationAuditor auditor) {
        this(toolRegistry, validator, auditor, new ObjectMapper());
    }

    public ToolExecutor(ToolRegistry toolRegistry,
                        ToolInvocationValidator validator,
                        ToolInvocationAuditor auditor,
                        ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.validator = validator == null ? NoopToolInvocationValidator.INSTANCE : validator;
        this.auditor = auditor == null ? NoopToolInvocationAuditor.INSTANCE : auditor;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 LLM 要求的一次工具调用。
     *
     * @param sessionId 会话 ID（审计关联必需，FR-017）
     * @param call      LLM 返回的工具调用
     * @return 工具执行结果（失败时为携带原因的失败结果，不抛异常）
     */
    public ToolResult execute(String sessionId, LlmClient.ToolCall call) {
        long startedAt = System.currentTimeMillis();
        String toolName = call == null ? null : call.name();
        String parameters = call == null ? null : call.arguments();
        String profileName = currentProfileName();

        if (toolName == null || toolName.isBlank()) {
            logger.warn("模型返回了空的工具名，已忽略");
            return auditAndReturn(sessionId, profileName, toolName, parameters,
                    ToolResult.fail("模型返回的工具名为空", false), startedAt);
        }

        OryxTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            logger.warn("未找到工具: {}（当前已注册: {}）", toolName, toolRegistry.all().keySet());
            return auditAndReturn(sessionId, profileName, toolName, parameters,
                    ToolResult.fail("未找到工具: " + toolName, false), startedAt);
        }

        Map<String, Object> input = parseArguments(parameters);

        // 执行前必经校验（Sandbox 等规则后续接入）
        try {
            validator.validate(tool, input);
        } catch (RuntimeException e) {
            logger.warn("工具前置校验未通过: tool={}, error={}", toolName, e.getMessage());
            return auditAndReturn(sessionId, profileName, toolName, parameters,
                    ToolResult.fail("前置校验未通过: " + e.getMessage(), false), startedAt);
        }

        logger.debug("执行工具: sessionId={}, tool={}", sessionId, toolName);
        try {
            ToolResult result = executeWithTimeout(tool, input);
            if (result == null) {
                result = ToolResult.fail("工具未返回结果", false);
            }
            long latencyMs = System.currentTimeMillis() - startedAt;
            logger.debug("工具执行完成: tool={}, success={}, 耗时={}ms", toolName, result.isSuccess(), latencyMs);
            return auditAndReturn(sessionId, profileName, toolName, parameters, result, startedAt);
        } catch (RuntimeException e) {
            // 工具内部异常转为失败结果，不中断整个处理
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            logger.warn("工具执行异常，已转为失败结果: tool={}, error={}", toolName, message);
            return auditAndReturn(sessionId, profileName, toolName, parameters,
                    ToolResult.fail(message, false), startedAt);
        }
    }

    /**
     * 批量顺序执行（核心阶段不做并行）。
     */
    public List<ToolResult> executeAll(String sessionId, List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        return toolCalls.stream()
                .map(call -> execute(sessionId, call))
                .toList();
    }

    /**
     * 在独立虚拟线程里执行工具，并施加单次超时（{@code settings.tool_timeout_seconds}）。
     *
     * <p>为什么必须包一层：工具是外部能力的入口（HTTP / shell / MCP），卡住是常态而非异常。
     * 不设边界时，一次挂住的 {@code http_get} 会一直占着请求线程——而整体 60 秒的检查
     * 发生在轮次边界，工具调用内部根本执行不到它。</p>
     *
     * <p>{@link AgentContext} 是 ThreadLocal，跨线程不会自动继承，所以这里显式搬运
     * profile 与 runId：{@code notify} 这类工具要靠它读当前 Profile 的通知渠道。</p>
     *
     * <p>超时后只能 {@code interrupt}（对阻塞 IO 有效，对纯 CPU 循环无效），
     * 因此这是"主流程不被拖住"的兜底，不是强制终止——保住链路比杀死工具更重要。</p>
     */
    private ToolResult executeWithTimeout(OryxTool tool, Map<String, Object> input) {
        Profile profile = AgentContext.current();
        String runId = AgentContext.currentRunId();
        int timeoutSeconds = profile == null
                ? Profile.DEFAULT_TOOL_TIMEOUT_SECONDS
                : profile.toolTimeoutSeconds();

        AtomicReference<ToolResult> holder = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Thread worker = Thread.ofVirtual().start(() -> {
            AgentContext.set(profile, runId);
            try {
                holder.set(tool.execute(input));
            } catch (RuntimeException e) {
                failure.set(e);
            } finally {
                AgentContext.clear();
            }
        });

        try {
            worker.join(Duration.ofSeconds(timeoutSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待工具执行时被中断: " + tool.getName(), e);
        }

        if (worker.isAlive()) {
            worker.interrupt();
            throw new IllegalStateException(
                    "工具执行超时（上限 " + timeoutSeconds + " 秒）: " + tool.getName());
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return holder.get();
    }

    /**
     * 写审计后返回结果。审计失败<b>不得</b>阻断主流程（FR-020），因此这里兜住审计异常。
     */
    private ToolResult auditAndReturn(String sessionId,
                                      String profileName,
                                      String toolName,
                                      String parameters,
                                      ToolResult result,
                                      long startedAt) {
        long latencyMs = System.currentTimeMillis() - startedAt;
        try {
            auditor.record(sessionId, profileName, toolName, parameters,
                    result.isSuccess(), result.getError(), result.getContent(), latencyMs);
        } catch (RuntimeException e) {
            // 审计缺失必须显式可见，但绝不能因此让工具结果丢失
            logger.error("工具审计写入失败（已忽略，不影响本次调用）: tool={}, error={}",
                    toolName, e.getMessage(), e);
        }
        return result;
    }

    private String currentProfileName() {
        Profile profile = AgentContext.current();
        return profile == null ? null : profile.getName();
    }

    private Map<String, Object> parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(arguments, new TypeReference<>() {
            });
        } catch (Exception e) {
            // 参数不是合法 JSON：降级为原样参数，不抛异常
            logger.debug("工具参数不是合法 JSON，按原样传入: {}", e.getMessage());
            return Map.of("raw", arguments);
        }
    }
}
