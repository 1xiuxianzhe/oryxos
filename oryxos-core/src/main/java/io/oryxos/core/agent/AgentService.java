package io.oryxos.core.agent;

import io.oryxos.core.audit.AgentRunAuditor;
import io.oryxos.core.audit.NoopAgentRunAuditor;
import io.oryxos.core.llm.LlmClient.TokenUsage;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.react.ModelCallException;
import io.oryxos.core.react.ProcessingTimeoutException;
import io.oryxos.core.react.ReActLoop;
import io.oryxos.core.react.ReActResult;
import io.oryxos.core.react.StopReason;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.UUID;

/**
 * AgentService —— 外部 Channel / Web API / 定时触发调用的统一入口。
 *
 * <p>处理一条用户消息的编排者：拿配置 → 拿会话 → 调循环 → 落审计 → 持久化会话。</p>
 *
 * <p>三个非讲不可的机制：</p>
 * <ol>
 *   <li>{@link AgentContext}：工具执行时需要知道"当前是哪个 Agent"，处理开始放入、
 *       处理结束（<b>含异常</b>）清理，否则复用该线程的下一个请求会拿到别人的配置。
 *       同时携带本次处理的 {@code runId}，供存储层把明细审计挂到请求记录上。</li>
 *   <li><b>请求级审计</b>（{@link AgentRunAuditor}）：一次处理一条记录，
 *       成功与失败都要落到终态。用户问了什么、用了哪个模型、花了多少 token、
 *       跑了几轮、成没成——后台一次查询就能拿到。</li>
 *   <li><b>MDC 里的 runId</b>：处理开始注入、结束清理。这样 {@code ReActLoop} /
 *       {@code ToolExecutor} / 存储层审计打出的每一行日志都自带同一个 runId，
 *       运维可以直接把日志与 {@code agent_runs} 里的那条记录对上（FR-021 / SC-010）。</li>
 * </ol>
 *
 * <p>失败路径的三件事必须同时做到：写终态审计、记带停止原因的日志、<b>持久化已累积的会话</b>。</p>
 */
public class AgentService {

    /** MDC key：与 {@code logging.pattern} 中的 {@code %X{runId}} 对应。 */
    public static final String MDC_RUN_ID = "runId";

    /** MDC key：会话 ID。一次处理的全部日志据此串成一条主线（跨 CLI / REST / 定时同一口径）。 */
    public static final String MDC_SESSION_ID = "sessionId";

    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);

    private final ProfileRegistry profiles;
    private final SessionManager sessions;
    private final ReActLoop reActLoop;
    private final AgentRunAuditor runAuditor;

    public AgentService(ProfileRegistry profiles, SessionManager sessions, ReActLoop reActLoop) {
        this(profiles, sessions, reActLoop, NoopAgentRunAuditor.INSTANCE);
    }

    public AgentService(ProfileRegistry profiles,
                        SessionManager sessions,
                        ReActLoop reActLoop,
                        AgentRunAuditor runAuditor) {
        this.profiles = profiles;
        this.sessions = sessions;
        this.reActLoop = reActLoop;
        this.runAuditor = runAuditor == null ? NoopAgentRunAuditor.INSTANCE : runAuditor;
    }

    /**
     * 处理一条用户消息。
     *
     * @param sessionId   会话 ID（为空则新建）
     * @param profileName Profile 名
     * @param channel     渠道名
     * @param userId      用户标识
     * @param userMessage 用户输入
     * @return Agent 回复
     */
    public String process(String sessionId, String profileName, String channel,
                          String userId, String userMessage) {
        Session session = resolveSession(sessionId, profileName, channel, userId);
        return doProcess(session, profileName, userMessage);
    }

    /** 便捷入口：直接给一个已存在的 Session。 */
    public String process(Session session, String userMessage) {
        return doProcess(session, session.getProfileName(), userMessage);
    }

    /**
     * 处理的外壳：负责 runId/日志上下文/会话落库这些"无论成败都要做"的事，
     * 真正的分支逻辑在 {@link #dispatch}。
     */
    private String doProcess(Session session, String profileName, String userMessage) {
        String runId = UUID.randomUUID().toString();
        long beganAt = System.currentTimeMillis();
        MDC.put(MDC_RUN_ID, runId);
        MDC.put(MDC_SESSION_ID, session.getSessionId());
        try {
            return dispatch(session, profileName, userMessage, runId, beganAt);
        } finally {
            AgentContext.clear();
            MDC.remove(MDC_RUN_ID);
            MDC.remove(MDC_SESSION_ID);
            // 成功、超时、模型报错都要把已累积的历史留下来（FR-009 / US3 场景 3）
            persistQuietly(session);
        }
    }

    private String dispatch(Session session, String profileName, String userMessage,
                            String runId, long beganAt) {
        Profile profile = profiles.getProfile(profileName);
        if (profile == null) {
            // 配置错误也要留痕：用户确实发起了这次请求，后台必须看得到它失败了、为什么失败
            started(runId, session, profileName, userMessage, null, null);
            finished(runId, StopReason.AGENT_CONFIG_ERROR, 0, 0, null,
                    "Profile 不存在: " + profileName);
            logger.error("[Agent] 处理结束（{}）: sessionId={}, runId={}, profile={}, 耗时={}ms, error=Profile 不存在",
                    StopReason.AGENT_CONFIG_ERROR, session.getSessionId(), runId, profileName,
                    System.currentTimeMillis() - beganAt);
            throw new AgentConfigurationException(profileName);
        }

        started(runId, session, profileName, userMessage, providerName(profile), modelName(profile));
        AgentContext.set(profile, runId);
        try {
            logger.info("[Agent] 处理开始: sessionId={}, runId={}, profile={}, channel={}, model={}",
                    session.getSessionId(), runId, profileName, session.getChannel(), modelName(profile));

            ReActResult result = reActLoop.runResult(session.getSessionId(), session, userMessage, profile);
            finished(runId, result.stopReason(), result.iterations(), result.toolCallCount(),
                    result.totalUsage(), null);

            logger.info("[Agent] 处理结束（{}）: sessionId={}, runId={}, 轮数={}, 工具调用={}, 总耗时={}ms, 消息数={}",
                    result.stopReason(), session.getSessionId(), runId, result.iterations(),
                    result.toolCallCount(), System.currentTimeMillis() - beganAt,
                    session.getMessages().size());
            return result.text();
        } catch (ProcessingTimeoutException e) {
            finished(runId, StopReason.TIMEOUT, e.getCompletedIterations(), e.getToolCallCount(),
                    e.getUsage(), e.getMessage());
            logger.warn("[Agent] 处理结束（{}）: sessionId={}, runId={}, 已执行轮数={}, 工具调用={}, 已耗时={}ms, 上限={}秒",
                    StopReason.TIMEOUT, session.getSessionId(), runId, e.getCompletedIterations(),
                    e.getToolCallCount(), System.currentTimeMillis() - beganAt, e.getTimeoutSeconds());
            throw e;
        } catch (ModelCallException e) {
            finished(runId, StopReason.MODEL_ERROR, 0, 0, null, e.getMessage());
            logger.error("[Agent] 处理结束（{}）: sessionId={}, runId={}, 已耗时={}ms, error={}",
                    StopReason.MODEL_ERROR, session.getSessionId(), runId,
                    System.currentTimeMillis() - beganAt, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            finished(runId, StopReason.INTERNAL_ERROR, 0, 0, null, e.getMessage());
            logger.error("[Agent] 处理结束（{}）: sessionId={}, runId={}, 已耗时={}ms, error={}",
                    StopReason.INTERNAL_ERROR, session.getSessionId(), runId,
                    System.currentTimeMillis() - beganAt, e.getMessage(), e);
            throw e;
        }
    }

    private Session resolveSession(String sessionId, String profileName, String channel, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return sessions.create(profileName, channel, userId);
        }
        Session existing = sessions.get(sessionId);
        return existing != null ? existing : sessions.create(sessionId, profileName, channel, userId);
    }

    private void started(String runId, Session session, String profileName, String userMessage,
                         String provider, String model) {
        try {
            runAuditor.recordStarted(runId, session.getSessionId(), profileName, session.getChannel(),
                    session.getUserId(), userMessage, provider, model);
        } catch (RuntimeException e) {
            // 审计缺失必须显式可见，但绝不能影响业务（FR-020）
            logger.error("[Agent] 请求级审计（started）写入失败: runId={}, error={}",
                    runId, e.getMessage(), e);
        }
    }

    private void finished(String runId, StopReason stopReason, int iterations, int toolCallCount,
                          TokenUsage usage, String errorMessage) {
        try {
            runAuditor.recordFinished(runId, stopReason, iterations, toolCallCount, usage, errorMessage);
        } catch (RuntimeException e) {
            logger.error("[Agent] 请求级审计（finished）写入失败: runId={}, error={}",
                    runId, e.getMessage(), e);
        }
    }

    /** 会话落库：失败只记 ERROR，绝不用新异常掩盖原来的业务异常。 */
    private void persistQuietly(Session session) {
        try {
            sessions.save(session);
        } catch (RuntimeException e) {
            logger.error("[Agent] 会话持久化失败（不阻断原始异常）: sessionId={}, error={}",
                    session.getSessionId(), e.getMessage(), e);
        }
    }

    private static String providerName(Profile profile) {
        return profile.getProvider() == null ? null : profile.getProvider().getName();
    }

    private static String modelName(Profile profile) {
        return profile.getProvider() == null ? null : profile.getProvider().getModel();
    }
}
