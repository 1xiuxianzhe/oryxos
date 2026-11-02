package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.dto.SessionDtos;
import io.oryxos.web.exception.SessionArchivedException;
import io.oryxos.web.exception.SessionNotFoundException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * SessionApiController —— 会话管理端点（创建 / 列表 / 详情 / 发消息 / 归档）。
 *
 * <p>Controller 只做三件事：参数校验、委托、响应包装。真正处理走
 * {@link AgentService#process(Session, String)}，与 CLI、定时任务完全同一条路径。</p>
 *
 * <p>两处刻意的取舍：</p>
 * <ul>
 *   <li><b>列表只给消息条数</b>：把历史整段搬进列表会让 100 个会话的响应变成几 MB；</li>
 *   <li><b>详情最多 100 条</b>并显式返回 {@code messageLimit}：静默截断会让调用方
 *       以为"这轮对话只有 100 句"，从而误判上下文。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionApiController {

    /** Web 渠道标识：与 CLI 的 {@code cli}、定时任务的 {@code scheduler} 区分。 */
    public static final String CHANNEL = "web";

    /** 单条消息上限（UTF-8 字节）：超出直接拒绝，不把超大请求带进 ReAct 循环。 */
    public static final int MAX_MESSAGE_BYTES = 32 * 1024;

    /** 详情返回的消息条数上限。 */
    public static final int MESSAGE_LIMIT = 100;

    private static final String DEFAULT_PROFILE = "default";
    private static final String DEFAULT_USER = "anonymous";

    private final AgentService agentService;
    private final SessionManager sessionManager;

    public SessionApiController(AgentService agentService, SessionManager sessionManager) {
        this.agentService = agentService;
        this.sessionManager = sessionManager;
    }

    /**
     * 创建会话；渠道固定 {@code web}。
     *
     * <p><b>幂等</b>：同一 {@code (channel, userId, profile)} 已有活跃会话时直接返回它，
     * 不新建、也不报错。两条理由：</p>
     * <ul>
     *   <li>{@code sessions} 表对这三个字段有唯一约束，重复 POST 若硬插会变成 500；</li>
     *   <li>对调用方来说"给我一个能聊的会话"本来就是幂等语义——拿到 id 就能接着发消息。</li>
     * </ul>
     *
     * <p>已归档的会话不满足幂等条件（{@code getOrCreate} 只认 active），此时新建一条：
     * 归档是"这段对话结束了"，不是"这个身份不能再开新对话"。</p>
     */
    @PostMapping
    public SessionDtos.Summary create(@RequestBody(required = false) SessionDtos.CreateSession request) {
        String profile = defaultIfBlank(request == null ? null : request.profile(), DEFAULT_PROFILE);
        String userId = defaultIfBlank(request == null ? null : request.userId(), DEFAULT_USER);

        Session session = sessionManager.getOrCreate(CHANNEL, userId, profile);
        // 命中已有会话时这次 save 是幂等覆盖；新建时需要它落盘，重启后管理平台才看得到
        sessionManager.save(session);
        return toSummary(session);
    }

    /** 会话列表，最近活跃在前。存储为空时返回空数组而非报错。 */
    @GetMapping
    public List<SessionDtos.Summary> list() {
        return sessionManager.list().stream()
                .sorted(Comparator.comparing(Session::getLastActiveAt).reversed())
                .map(this::toSummary)
                .toList();
    }

    /** 会话详情：摘要 + 最近 {@value #MESSAGE_LIMIT} 条消息。 */
    @GetMapping("/{id}")
    public SessionDtos.Detail detail(@PathVariable String id) {
        Session session = requireSession(id);
        List<Session.Message> messages = session.getMessages();
        int from = Math.max(0, messages.size() - MESSAGE_LIMIT);
        List<SessionDtos.MessageView> views = messages.subList(from, messages.size()).stream()
                .map(message -> new SessionDtos.MessageView(message.getRole(), message.getContent()))
                .toList();
        return new SessionDtos.Detail(toSummary(session), views, MESSAGE_LIMIT);
    }

    /**
     * 发一条消息并同步返回回复。
     *
     * <p>空白、超限、会话不存在、已归档四种情况都在进入引擎之前拦下——
     * 尤其"已归档"，让写入落到死会话上比报错更难查。</p>
     */
    @PostMapping("/{id}/messages")
    public SessionDtos.MessageResponse send(@PathVariable String id,
                                            @RequestBody(required = false) SessionDtos.SendMessage request) {
        String content = validateContent(request);
        Session session = requireSession(id);
        if ("archived".equalsIgnoreCase(session.getStatus())) {
            throw new SessionArchivedException(id);
        }

        String reply = agentService.process(session, content);
        return new SessionDtos.MessageResponse(
                session.getSessionId(), "assistant", reply == null ? "" : reply, "ok");
    }

    /** 归档会话：不删除数据，只标记状态；归档后不可再发消息。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> archive(@PathVariable String id) {
        requireSession(id);
        sessionManager.archive(id);
        return Map.of("sessionId", id, "status", "archived");
    }

    // ---------- 内部 ----------

    private Session requireSession(String id) {
        Session session = sessionManager.get(id);
        if (session == null) {
            throw new SessionNotFoundException(id);
        }
        return session;
    }

    private SessionDtos.Summary toSummary(Session session) {
        return new SessionDtos.Summary(
                session.getSessionId(),
                session.getProfileName(),
                session.getChannel(),
                session.getUserId(),
                session.getStatus(),
                session.getMessages().size(),
                session.getCreatedAt().toString(),
                session.getLastActiveAt().toString());
    }

    /**
     * 校验消息内容。
     *
     * <p>限额按 <b>UTF-8 字节</b>而不是字符数：中文一个字 3 字节，按字符算会让限额形同虚设。</p>
     */
    private static String validateContent(SessionDtos.SendMessage request) {
        if (request == null || request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        int bytes = request.content().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException(
                    "content 超出单条上限: " + bytes + " 字节 > " + MAX_MESSAGE_BYTES + " 字节");
        }
        return request.content();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
