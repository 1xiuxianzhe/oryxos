package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentNotFoundException;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.profile.SkippedAgent;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.dto.AgentAdminDtos;
import io.oryxos.web.dto.SessionDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * AgentAdminController —— 管理台"能管"的那一半：增删改查、生成、文件、会话、记忆。
 *
 * <p>与 {@link AgentApiController} 的分工：那个是<b>业务调用</b>（发消息、无状态 invoke），
 * 这个是<b>管理与配置</b>。两者共用同一个 {@link AgentLifecycleService} / {@link AgentService}，
 * 不存在"管理台走的另一条链路"。</p>
 *
 * <p><b>三处刻意的取舍：</b></p>
 * <ul>
 *   <li><b>列表与详情以注册表为准</b>：注册表才是"当前真能跑的 Agent"；磁盘上多出一个坏目录，
 *       它会出现在 {@code skipped} 里而不是伪装成可用 Agent。</li>
 *   <li><b>对外不返回绝对路径</b>：目录一律写成 {@code agents/<name>}。绝对路径属于内幕，
 *       而且换个部署位置前端就全错。</li>
 *   <li><b>会话固定三元组</b>（{@code admin / console / <name>}）：管理台里的对话与业务对话分开，
 *       既不会污染用户会话，也不用为每个操作者维护一份调试历史。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentAdminController {

    /** Agent 管理台的会话渠道，与 {@code web} / {@code cli} / {@code scheduler} 区分。 */
    static final String CHANNEL = AgentLifecycleService.ADMIN_CHANNEL;

    /** Agent 管理台的会话用户标识（没有真实操作者，固定值换"同一 Agent 复用同一会话"）。 */
    static final String USER = AgentLifecycleService.ADMIN_USER;

    private static final int MESSAGE_LIMIT = 100;
    private static final int MAX_MESSAGE_BYTES = 32 * 1024;
    private static final int MAX_LISTED_FILES = 200;

    private static final Logger logger = LoggerFactory.getLogger(AgentAdminController.class);

    private final AgentLifecycleService lifecycle;
    private final ProfileRegistry profileRegistry;
    private final SessionManager sessionManager;
    private final AgentService agentService;
    private final MemoryService memoryService;

    public AgentAdminController(AgentLifecycleService lifecycle,
                                ProfileRegistry profileRegistry,
                                SessionManager sessionManager,
                                AgentService agentService,
                                MemoryService memoryService) {
        this.lifecycle = lifecycle;
        this.profileRegistry = profileRegistry;
        this.sessionManager = sessionManager;
        this.agentService = agentService;
        this.memoryService = memoryService;
    }

    // ------------------------------------------------------------------
    // 增删改查
    // ------------------------------------------------------------------

    /** 列表：注册表里的 Agent + 运行期被跳过的目录（后者解释"我丢进去为什么没生效"）。 */
    @GetMapping
    public AgentAdminDtos.AgentListView list() {
        Map<String, String> skipped = lifecycle.skippedAgents().stream()
                .collect(java.util.stream.Collectors.toMap(
                        SkippedAgent::dirName,
                        item -> item.reason() == null ? "" : item.reason(),
                        (first, second) -> second));

        List<AgentAdminDtos.AgentSummary> agents = profileRegistry.getAllProfiles().values().stream()
                .sorted(Comparator.comparing(profile -> profile.getName() == null ? "" : profile.getName()))
                .map(profile -> toSummary(profile, skipped.get(profile.getName())))
                .toList();

        List<AgentAdminDtos.SkippedAgentView> skippedViews = skipped.entrySet().stream()
                .filter(entry -> !profileRegistry.exists(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new AgentAdminDtos.SkippedAgentView(entry.getKey(), entry.getValue()))
                .toList();

        return new AgentAdminDtos.AgentListView(agents, skippedViews);
    }

    /** 详情；注册表里没有就 404（磁盘上有坏目录请查 {@code /agents} 的 skipped）。 */
    @GetMapping("/{name}")
    public AgentAdminDtos.AgentDetail detail(@PathVariable String name) {
        return toDetail(requireProfile(name));
    }

    /** 新建：写脚手架 → 注册 → 立即可见。失败回滚，不留半个 Agent。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentAdminDtos.AgentDetail create(@RequestBody(required = false) AgentAdminDtos.CreateAgent request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空：需要 {name, description}");
        }
        Profile profile = lifecycle.create(request.name(), request.description());
        logger.info("[Web] 已创建 Agent: {}", profile.getName());
        return toDetail(profile);
    }

    /** 覆盖 AGENT.md：先校验后写盘，失败保持旧内容与旧注册。 */
    @PutMapping("/{name}")
    public AgentAdminDtos.AgentDetail update(@PathVariable String name,
                                             @RequestBody(required = false) AgentAdminDtos.UpdateAgent request) {
        if (request == null || request.markdown() == null) {
            throw new IllegalArgumentException("请求体不能为空：需要 {markdown}");
        }
        return toDetail(lifecycle.update(name, request.markdown()));
    }

    /** 下线：注销定时 → 移出注册表 → 归档（不物理删除）。 */
    @DeleteMapping("/{name}")
    public AgentAdminDtos.DeletedAgent delete(@PathVariable String name) {
        Path archived = lifecycle.delete(name);
        String archivedTo = archived == null ? null : "archive/" + archived.getFileName();
        logger.info("[Web] 已删除 Agent: name={}, archivedTo={}", name, archivedTo);
        return new AgentAdminDtos.DeletedAgent(name, archivedTo);
    }

    // ------------------------------------------------------------------
    // 生成与保存
    // ------------------------------------------------------------------

    /** 按描述生成草稿：<b>不落盘、不注册</b>，用户改完再调 {@code /files}。 */
    @PostMapping("/{name}/generate-files")
    public AgentAdminDtos.GeneratedFiles generate(@PathVariable String name,
                                                  @RequestBody(required = false) AgentAdminDtos.GenerateRequest request) {
        requireProfile(name);
        if (request == null || request.description() == null || request.description().isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        Map<String, String> files = lifecycle.generate(name, request.description());
        return new AgentAdminDtos.GeneratedFiles(files, "草稿：尚未写入磁盘，确认后调用 POST /files 生效");
    }

    /** 保存一组文件并让其生效（AGENT.md 会触发重注册）。 */
    @PostMapping("/{name}/files")
    public AgentAdminDtos.WrittenFiles saveFiles(@PathVariable String name,
                                                 @RequestBody(required = false) AgentAdminDtos.SaveFiles request) {
        requireProfile(name);
        if (request == null || request.files() == null || request.files().isEmpty()) {
            throw new IllegalArgumentException("files 不能为空：需要 {files: {相对路径: 内容}}");
        }
        List<String> written = lifecycle.saveFiles(name, request.files());
        return new AgentAdminDtos.WrittenFiles(name, written);
    }

    // ------------------------------------------------------------------
    // 记忆与会话
    // ------------------------------------------------------------------

    /** 该 Agent 自己的长期记忆（读口径与注入上下文一致，只读）。 */
    @GetMapping("/{name}/memory")
    public AgentAdminDtos.MemoryView memory(@PathVariable String name) {
        Profile profile = requireProfile(name);
        String content = memoryService.snapshot(profile.getAgentDir());
        if (content == null) {
            content = "";
        }
        return new AgentAdminDtos.MemoryView(content, content.length(), MemoryApiController.NOTE);
    }

    /** 该 Agent 的管理台会话（固定三元组，幂等复用）。 */
    @GetMapping("/{name}/session")
    public AgentAdminDtos.AgentSession session(@PathVariable String name) {
        requireProfile(name);
        Session session = sessionManager.getOrCreate(CHANNEL, USER, name);
        return toSessionView(session);
    }

    /** 在管理台会话里发一条消息，同步返回回复。 */
    @PostMapping("/{name}/session/messages")
    public SessionDtos.MessageResponse sendMessage(@PathVariable String name,
                                                   @RequestBody(required = false) AgentAdminDtos.SendMessage request) {
        requireProfile(name);
        String content = validateContent(request);

        Session session = sessionManager.getOrCreate(CHANNEL, USER, name);
        String reply = agentService.process(session, content);

        return new SessionDtos.MessageResponse(
                session.getSessionId(), "assistant", reply == null ? "" : reply, "ok");
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Profile requireProfile(String name) {
        Profile profile = profileRegistry.getProfile(name);
        if (profile == null) {
            throw new AgentNotFoundException(name);
        }
        return profile;
    }

    private AgentAdminDtos.AgentSummary toSummary(Profile profile, String skippedReason) {
        Profile.ProviderConfig provider = profile.getProvider();
        return new AgentAdminDtos.AgentSummary(
                profile.getName(),
                profile.getDescription(),
                provider == null ? null : provider.getName(),
                provider == null ? null : provider.getModel(),
                profile.getSchedules() == null ? 0 : profile.getSchedules().size(),
                profile.isFromAgentDirectory(),
                skippedReason);
    }

    private AgentAdminDtos.AgentDetail toDetail(Profile profile) {
        Profile.ProviderConfig provider = profile.getProvider();
        Path agentDir = profile.getAgentDir();
        return new AgentAdminDtos.AgentDetail(
                profile.getName(),
                profile.getDescription(),
                provider == null ? null : provider.getName(),
                provider == null ? null : provider.getModel(),
                profile.getTools() == null ? List.of()
                        : profile.getTools().stream().map(Profile.ToolConfig::getName).toList(),
                profile.getSchedules() == null ? List.of()
                        : profile.getSchedules().stream()
                        .map(schedule -> schedule.getId() + " @ " + schedule.getCron())
                        .toList(),
                profile.getSettings() == null ? Map.of() : profile.getSettings(),
                agentDir == null ? null : "agents/" + profile.getName(),
                listFiles(agentDir));
    }

    /** 目录内文件清单（最多 {@value #MAX_LISTED_FILES} 条，只给相对路径）。 */
    private static List<String> listFiles(Path agentDir) {
        if (agentDir == null || !Files.isDirectory(agentDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(agentDir, 3)) {
            return stream.filter(Files::isRegularFile)
                    .map(path -> agentDir.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .limit(MAX_LISTED_FILES)
                    .toList();
        } catch (IOException e) {
            logger.warn("[Web] 列出 Agent 文件失败: dir={}, reason={}", agentDir, e.getMessage());
            return List.of();
        }
    }

    private AgentAdminDtos.AgentSession toSessionView(Session session) {
        List<Session.Message> messages = session.getMessages();
        int from = Math.max(0, messages.size() - MESSAGE_LIMIT);
        List<SessionDtos.MessageView> views = messages.subList(from, messages.size()).stream()
                .map(message -> new SessionDtos.MessageView(message.getRole(), message.getContent()))
                .toList();
        return new AgentAdminDtos.AgentSession(
                session.getSessionId(), session.getProfileName(), views, MESSAGE_LIMIT);
    }

    private static String validateContent(AgentAdminDtos.SendMessage request) {
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
}
