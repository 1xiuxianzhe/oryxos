package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.dto.AgentDtos;
import io.oryxos.web.dto.ChatRequest;
import io.oryxos.web.dto.ChatResponse;
import io.oryxos.web.exception.ProfileNotFoundException;
import io.oryxos.web.exception.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * AgentApiController —— 两个 Agent 入口，一个共用引擎。
 *
 * <ul>
 *   <li>{@code POST /api/v1/agent/chat}：既有兼容端点，按 {@code (web, userId, profile)}
 *       <b>复用同一活动会话</b>，适合"接着聊"；</li>
 *   <li>{@code POST /api/v1/agents/{name}/invoke}：<b>无状态调用</b>，每次新建一次性会话
 *       （{@code channel=invoke}），适合业务系统的"问一句就走"。</li>
 * </ul>
 *
 * <p>两者都只做参数校验、会话解析、委托 {@link AgentService}、组装响应。
 * <b>不碰</b> ReAct / Provider / Tool / Memory——那些是核心层的职责。</p>
 *
 * <p>错误语义（见技术方案 §7.4）：参数错误 400、会话/Agent 不存在 404、
 * Provider 故障 503、Agent 超时 504、其余 500，统一由
 * {@code GlobalExceptionHandler} 转成 {@code errorCode/message/timestamp} 结构。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class AgentApiController {

    /** Web 渠道标识：与 CLI 的 {@code cli} 区分，两者各自维护独立的会话身份。 */
    public static final String CHANNEL = "web";

    /** 一次性调用的渠道标识：审计里一眼看出"这次不是一段对话，而是一次调用"。 */
    public static final String INVOKE_CHANNEL = "invoke";

    private static final String DEFAULT_PROFILE = "default";
    private static final String DEFAULT_USER = "anonymous";

    /** 单条消息上限（技术方案 §7.4）：超出直接拒绝，不把超大请求带进 ReAct 循环。 */
    private static final int MAX_MESSAGE_BYTES = 32 * 1024;

    private static final Logger logger = LoggerFactory.getLogger(AgentApiController.class);

    private final AgentService agentService;
    private final SessionManager sessionManager;
    private final ProfileRegistry profileRegistry;

    public AgentApiController(AgentService agentService,
                              SessionManager sessionManager,
                              ProfileRegistry profileRegistry) {
        this.agentService = agentService;
        this.sessionManager = sessionManager;
        this.profileRegistry = profileRegistry;
    }

    /**
     * 发一条消息给 Agent，同步返回回复（复用会话）。
     *
     * <p>整体耗时上限由 Profile 的 {@code settings.processing_timeout_seconds} 控制（默认 60 秒），
     * 由 {@code ReActLoop} 在步骤边界检查并在超限时抛 {@code ProcessingTimeoutException}，
     * 这里不再叠加一层 Web 侧超时，避免两个超时口径打架。</p>
     */
    @PostMapping("/agent/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String message = validateMessage(request == null ? null : request.message());
        String profile = defaultIfBlank(request.profile(), DEFAULT_PROFILE);
        String userId = defaultIfBlank(request.userId(), DEFAULT_USER);

        Session session = resolveSession(request.sessionId(), profile, userId);
        logger.debug("[Web] 对话请求: sessionId={}, profile={}, userId={}, 消息长度={}",
                session.getSessionId(), session.getProfileName(), userId, message.length());

        String content = agentService.process(session, message);

        return new ChatResponse(
                session.getSessionId(),
                // 回显会话自身的 profile：已有会话以它会话绑定的 profile 为准，不受本次请求参数影响
                session.getProfileName(),
                "assistant",
                content == null ? "" : content,
                "ok");
    }

    /**
     * 无状态调用：每次新建一次性会话，跑完即返回。
     *
     * <p>先查注册表再建会话——顺序反了的话，一个拼错的 Agent 名会留下一条永远没人看的空会话。</p>
     */
    @PostMapping("/agents/{name}/invoke")
    public AgentDtos.InvokeResponse invoke(@PathVariable String name,
                                           @RequestBody(required = false) AgentDtos.InvokeRequest request) {
        if (profileRegistry.getProfile(name) == null) {
            throw new ProfileNotFoundException(name);
        }
        String message = validateMessage(request == null ? null : request.message());
        String userId = defaultIfBlank(request == null ? null : request.userId(), DEFAULT_USER);

        Session session = sessionManager.create(name, INVOKE_CHANNEL, userId);
        logger.debug("[Web] 无状态调用: agent={}, sessionId={}, userId={}",
                name, session.getSessionId(), userId);

        String content = agentService.process(session, message);
        return new AgentDtos.InvokeResponse(session.getSessionId(), name, content == null ? "" : content);
    }

    /**
     * 解析本次请求要用的会话。
     *
     * <ul>
     *   <li>不传 {@code sessionId}：按 {@code (web, userId, profile)} <b>复用同一活动会话</b>。
     *       这与 CLI 的会话语义一致，连续提问就是同一段对话；换 {@code userId} 或 {@code profile}
     *       即得到一段全新对话。</li>
     *   <li>传了 {@code sessionId}：必须已存在，否则 404。已有会话以<b>会话绑定的 profile</b> 为准。</li>
     * </ul>
     */
    private Session resolveSession(String sessionId, String profile, String userId) {
        if (sessionId == null || sessionId.isBlank()) {
            return sessionManager.getOrCreate(CHANNEL, userId, profile);
        }
        Session session = sessionManager.get(sessionId);
        if (session == null) {
            throw new SessionNotFoundException(sessionId);
        }
        return session;
    }

    private static String validateMessage(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空");
        }
        int bytes = message.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException(
                    "message 超出单条上限: " + bytes + " 字节 > " + MAX_MESSAGE_BYTES + " 字节");
        }
        return message;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
