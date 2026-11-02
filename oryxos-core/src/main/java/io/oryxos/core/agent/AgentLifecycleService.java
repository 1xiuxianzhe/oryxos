package io.oryxos.core.agent;

import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.llm.LlmRequest;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentLoader;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.profile.SkippedAgent;
import io.oryxos.core.schedule.AgentScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentLifecycleService —— Agent 的"上线 / 更新 / 下线"编排。
 *
 * <p>这是本节的核心：把 <b>磁盘目录</b>、<b>注册表</b>、<b>调度器</b> 三份状态按固定顺序改到一致。
 * 三者的关系是：目录是事实来源，注册表是"当前可用的 Agent"，调度器是"当前在跑的定时"。
 * 只改其中一两个，就会出现各种"看起来在、其实跑不动"的状态。</p>
 *
 * <p><b>四条不可违背的顺序：</b></p>
 * <ol>
 *   <li><b>注册必须幂等</b>：{@link AgentScheduler#registerProfile} 对同一任务键是 {@code handles.put}，
 *       旧句柄不会被取消。所以必须先 {@link AgentScheduler#unregisterProfile(String)} 再注册，
 *       否则改一次 {@code schedules} 就有两条 cron 一起跑。</li>
 *   <li><b>先解析再动旧状态</b>：{@code derive} 在 {@code unregister} 之前。新内容非法时抛异常，
 *       旧注册毫发无损——顺序反了就会"改错一个字，Agent 从运行时消失"。</li>
 *   <li><b>先校验再写盘</b>：{@code update} 先用 {@code deriveFromText} 验一遍。
 *       "写了再回滚"只是在回滚失败时更糟，不能当作第一道防线。</li>
 *   <li><b>删除不物理删</b>：注销定时 → 移出注册表 → 整目录归档。归档目录是审计数据与人心的锚点。</li>
 * </ol>
 *
 * <p>本类同时实现 {@link WorkspaceWatcher.AgentChangeListener}：<b>API 建 Agent</b> 与
 * <b>手工丢目录</b> 最终都走同一个 {@link #register(String)}，不是两套代码。</p>
 */
public class AgentLifecycleService implements WorkspaceWatcher.AgentChangeListener {

    /** 生成 Agent 文件时使用的审计会话标识（llm_calls 里可据此区分"生成"与"业务对话"）。 */
    public static final String AUTHOR_SESSION_ID = "author";

    /** 生成用的临时 Profile 名（只用于路由，不进注册表）。 */
    static final String AUTHOR_PROFILE_NAME = "author";

    /** Agent 管理台固定会话的三元组：渠道 / 用户 / Agent 名。 */
    public static final String ADMIN_CHANNEL = "admin";
    public static final String ADMIN_USER = "console";

    /** Agent 名长度上限（也是目录名长度上限）。 */
    static final int MAX_NAME_LENGTH = 64;

    /** 单个文件写入上限：管理台不是网盘，超过这个量说明用法不对。 */
    static final int MAX_FILE_CHARS = 256 * 1024;

    /** 模型输出的文件分隔行：{@code === FILE: AGENT.md ===}。 */
    private static final Pattern FILE_HEADER =
            Pattern.compile("^={2,}\\s*FILE:\\s*(.+?)\\s*={2,}$", Pattern.CASE_INSENSITIVE);

    private static final Logger logger = LoggerFactory.getLogger(AgentLifecycleService.class);

    private final AgentStore store;
    private final AgentLoader loader;
    private final ProfileRegistry registry;
    private final AgentScheduler scheduler;
    private final LlmClient llmClient;
    private final String authorProvider;
    private final String authorModel;
    private final List<String> availableTools;

    /** 运行期被拒绝的目录（启动扫描之外的那些"改了但没生效"），供接口透出。 */
    private final List<SkippedAgent> skipped = new CopyOnWriteArrayList<>();

    /**
     * @param llmClient    生成能力用的客户端；为 {@code null} 时生成端点返回 503
     * @param authorProvider / {@code authorModel}：{@code oryxos.author.*}，缺失则生成不可用
     * @param availableTools 当前已注册的工具名（写进 prompt，避免模型编造工具）
     */
    public AgentLifecycleService(AgentStore store,
                                 AgentLoader loader,
                                 ProfileRegistry registry,
                                 AgentScheduler scheduler,
                                 LlmClient llmClient,
                                 String authorProvider,
                                 String authorModel,
                                 List<String> availableTools) {
        this.store = store;
        this.loader = loader;
        this.registry = registry;
        this.scheduler = scheduler;
        this.llmClient = llmClient;
        this.authorProvider = trimToNull(authorProvider);
        this.authorModel = trimToNull(authorModel);
        this.availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
    }

    // ------------------------------------------------------------------
    // 注册（API 与监听器共用）
    // ------------------------------------------------------------------

    /**
     * 把一个已存在的 Agent 目录注册进运行时（幂等）。
     *
     * @throws AgentDefinitionException 目录名非法、缺 {@code AGENT.md}、内容非法、provider 未声明
     */
    public Profile register(String name) {
        Path dir = store.agentDir(name);
        // 1. 先解析：这一步失败时旧注册必须原封不动
        AgentLoader.Derived derived = loader.derive(dir);
        Profile profile = derived.profile();

        // 2. 注销旧的定时任务（同 key 的句柄不取消会造成双份 cron）
        scheduler.unregisterProfile(name);
        // 3. 覆盖索引
        registry.register(profile);
        // 4. 按新配置重新注册定时任务
        int scheduled = scheduler.registerProfile(profile);

        clearSkipped(name);
        logger.info("[Agent] 已注册: name={}, provider={}, schedules={}, dir={}",
                name,
                profile.getProvider() == null ? "<无>" : profile.getProvider().getName(),
                scheduled,
                dir);
        return profile;
    }

    /**
     * 按名字与描述创建一个新 Agent 并立即上线。
     *
     * <p>失败时必须把已写下的目录删掉：否则会留下一个"目录在、注册失败"的半成品，
     * 它下次启动会被扫描到，于是错误被推迟到重启之后才爆发。</p>
     */
    public Profile create(String name, String description) {
        validateName(name);
        if (registry.exists(name) || store.exists(name)) {
            throw new IllegalArgumentException(
                    "Agent 已存在: " + name + "（如需重建请先删除，或换一个名字）");
        }
        if (store.defaultProvider() == null) {
            throw new IllegalArgumentException(
                    "当前实例未声明任何 provider（oryxos.providers），新建的 Agent 无法调用模型");
        }

        Path dir;
        try {
            dir = store.createScaffold(name, description);
        } catch (IOException e) {
            throw new IllegalStateException("创建 Agent 目录失败: " + e.getMessage(), e);
        }

        try {
            return register(name);
        } catch (RuntimeException e) {
            rollbackCreate(name, dir);
            throw e;
        }
    }

    /**
     * 覆盖 {@code AGENT.md} 并重新上线。
     *
     * <p>顺序：读旧内容 → 校验新内容 → 原子写 → 重注册。写盘之后若注册失败，
     * 恢复旧内容并重新注册旧内容——"磁盘上是什么，运行时就是什么"这条不变式不能被打破。</p>
     */
    public Profile update(String name, String markdown) {
        if (markdown == null) {
            throw new IllegalArgumentException("markdown 不能为空");
        }
        Path dir = store.agentDir(name);
        Path mainFile = dir.resolve(AgentLoader.MAIN_FILE);
        if (!Files.isRegularFile(mainFile)) {
            throw new AgentNotFoundException(name);
        }

        String previous;
        try {
            previous = Files.readString(mainFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取旧 AGENT.md 失败: " + e.getMessage(), e);
        }

        // 预校验：非法内容一个字节都不写
        loader.deriveFromText(name, dir, markdown);

        try {
            store.writeAtomically(mainFile, markdown);
            return register(name);
        } catch (IOException | RuntimeException e) {
            restore(name, dir, mainFile, previous);
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("写入 AGENT.md 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下线一个 Agent：注销定时 → 移出注册表 → 归档目录。
     *
     * @return 归档后的目录；目录本就不存在（legacy Profile）时返回 {@code null}
     */
    public Path delete(String name) {
        Profile existing = registry.getProfile(name);
        boolean onDisk = store.exists(name);
        if (existing == null && !onDisk) {
            throw new AgentNotFoundException(name);
        }

        // 顺序固定：先停定时（否则"注册表里没了、cron 还在跑"），再移出索引，最后归档
        scheduler.unregisterProfile(name);
        registry.remove(name);

        if (!onDisk) {
            logger.warn("[Agent] 已下线 legacy Profile（无目录可归档）: {}", name);
            return null;
        }

        try {
            Path archived = store.archive(name);
            clearSkipped(name);
            return archived;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Agent 已从运行时下线，但目录归档失败（目录仍在 " + store.agentDir(name)
                            + "，可手工处理）: " + e.getMessage(), e);
        }
    }

    /** 只注销、不归档（手工删目录路径）。幂等：不存在时只记日志。 */
    public void unregister(String name) {
        scheduler.unregisterProfile(name);
        registry.remove(name);
        clearSkipped(name);
        logger.info("[Agent] 已注销: {}", name);
    }

    // ------------------------------------------------------------------
    // 生成与保存
    // ------------------------------------------------------------------

    /**
     * 按描述生成 Agent 文件（<b>不落盘、不注册</b>）。
     *
     * <p>返回 {@code 相对路径 → 内容}；调用方展示、修改后通过 {@link #saveFiles} 保存。
     * 之所以不直接落盘：模型输出的第一版几乎总是要改的，而"生成即上线"会让改错的版本
     * 立刻跑起定时任务。</p>
     */
    public Map<String, String> generate(String name, String description) {
        validateName(name);
        requireModelforGeneration();

        String systemPrompt = generationPrompt(name, description);
        LlmRequest request = new LlmRequest(systemPrompt, "", List.of(), List.of());

        LlmClient.LlmResponse response;
        try {
            response = llmClient.call(AUTHOR_SESSION_ID, authorProfile(), request);
        } catch (RuntimeException e) {
            throw AgentGenerationException.unavailable("调用生成模型失败: " + e.getMessage());
        }

        String text = response == null ? "" : response.text();
        if (text == null || text.isBlank()) {
            throw AgentGenerationException.invalid("模型没有返回任何内容，请换个描述再试");
        }

        Map<String, String> files = parseGeneratedFiles(text);
        if (files.isEmpty()) {
            throw AgentGenerationException.invalid("模型输出里没有可识别的文件内容");
        }
        if (!files.containsKey(AgentLoader.MAIN_FILE)) {
            throw AgentGenerationException.invalid("模型输出缺少 " + AgentLoader.MAIN_FILE);
        }

        Path dir = store.agentDir(name);
        files.forEach((path, content) -> validateRelativePath(path));
        try {
            loader.deriveFromText(name, dir, files.get(AgentLoader.MAIN_FILE));
        } catch (AgentDefinitionException e) {
            throw AgentGenerationException.invalid(
                    "生成结果无法通过校验（" + e.getMessage() + "），请补充描述或手工编辑", e);
        }

        logger.info("[Agent] 生成草稿完成: name={}, files={}", name, files.keySet());
        return files;
    }

    /**
     * 保存一组文件并让改动生效。
     *
     * <p>两步纪律：<b>先整体校验 AGENT.md</b>（非法则一个字节都不写），再逐文件写入。
     * 非 {@code AGENT.md} 的文件先写、{@code AGENT.md} 最后写——这样重注册看到的永远是最终状态。</p>
     *
     * @return 实际写入的相对路径（按写入顺序）
     */
    public List<String> saveFiles(String name, Map<String, String> files) {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files 不能为空");
        }
        Path dir = store.agentDir(name);
        if (!Files.isRegularFile(dir.resolve(AgentLoader.MAIN_FILE))) {
            throw new AgentNotFoundException(name);
        }

        String agentMarkdown = files.get(AgentLoader.MAIN_FILE);
        if (agentMarkdown != null) {
            // 先整体校验：AGENT.md 不合法时，连其它文件也不写，避免"半个改动"
            loader.deriveFromText(name, dir, agentMarkdown);
        }

        Map<Path, String> targets = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String relative = validateRelativePath(entry.getKey());
            String content = entry.getValue() == null ? "" : entry.getValue();
            if (content.length() > MAX_FILE_CHARS) {
                throw new IllegalArgumentException(
                        "文件过大: " + relative + "（" + content.length() + " > " + MAX_FILE_CHARS + " 字符）");
            }
            Path target = dir.resolve(relative).normalize();
            if (!target.startsWith(dir)) {
                throw new IllegalArgumentException("路径越界: " + relative);
            }
            if (target.equals(dir)) {
                throw new IllegalArgumentException("path 必须是文件而非目录: " + relative);
            }
            targets.put(target, content);
        }

        List<String> written = new ArrayList<>();
        for (Map.Entry<Path, String> entry : targets.entrySet()) {
            String relative = dir.relativize(entry.getKey()).toString().replace('\\', '/');
            if (AgentLoader.MAIN_FILE.equals(relative)) {
                continue;
            }
            writeFile(entry.getKey(), entry.getValue());
            written.add(relative);
        }
        if (agentMarkdown != null) {
            update(name, agentMarkdown);
            written.add(AgentLoader.MAIN_FILE);
        }
        logger.info("[Agent] 文件已保存并生效: name={}, files={}", name, written);
        return written;
    }

    /** 运行期被拒绝的目录（启动扫描之外）。 */
    public List<SkippedAgent> skippedAgents() {
        return List.copyOf(skipped);
    }

    // ------------------------------------------------------------------
    // WorkspaceWatcher 回调
    // ------------------------------------------------------------------

    /**
     * 目录出现或 {@code AGENT.md} 变更。
     *
     * <p>失败只记日志并留下原因，<b>不</b>注销旧版本：一次手滑不该让线上 Agent 消失。</p>
     */
    @Override
    public void onChanged(Path agentDir) {
        String name = agentDir.getFileName() == null ? "" : agentDir.getFileName().toString();
        try {
            register(name);
        } catch (RuntimeException e) {
            recordSkipped(name, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            logger.error("[Agent] 目录变更未能生效，已保留旧版本: name={}, reason={}", name, e.getMessage());
        }
    }

    /** 目录消失：只注销"来自目录"的 Profile，不误伤同名 legacy Profile。 */
    @Override
    public void onRemoved(String name) {
        Profile existing = registry.getProfile(name);
        if (existing != null && !existing.isFromAgentDirectory()) {
            logger.warn("[Agent] 目录已删除，但注册表里的同名 legacy Profile 保留: {}", name);
            return;
        }
        unregister(name);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void validateName(String name) {
        if (!AgentLoader.isValidName(name)) {
            throw new IllegalArgumentException(
                    "Agent 名不合法（只允许小写字母、数字与中划线，且以字母或数字开头）: " + name);
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Agent 名过长（上限 " + MAX_NAME_LENGTH + " 字符）: " + name);
        }
    }

    private void rollbackCreate(String name, Path dir) {
        try {
            unregister(name);
        } catch (RuntimeException e) {
            logger.warn("[Agent] 创建回滚时注销失败（继续删目录）: name={}, reason={}", name, e.getMessage());
        }
        try {
            store.deleteTree(dir);
        } catch (IOException e) {
            logger.error("[Agent] 创建失败后删目录也失败，需手工清理: dir={}", dir, e);
        }
    }

    private void restore(String name, Path dir, Path mainFile, String previous) {
        try {
            store.writeAtomically(mainFile, previous);
            register(name);
            logger.warn("[Agent] 更新失败，已恢复旧 AGENT.md 并重新注册: name={}", name);
        } catch (IOException | RuntimeException e) {
            logger.error("[Agent] 更新失败且回滚失败，磁盘与注册表可能不一致: name={}, dir={}", name, dir, e);
        }
    }

    private void writeFile(Path target, String content) {
        try {
            store.writeAtomically(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("写入文件失败: " + target + "（" + e.getMessage() + "）", e);
        }
    }

    /** 相对路径校验：只允许 Agent 目录内的相对路径，拒绝绝对路径、{@code ..} 与空路径。 */
    private static String validateRelativePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("文件路径不能为空");
        }
        String relative = rawPath.replace('\\', '/').strip();
        if (relative.startsWith("/") || relative.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("文件路径必须是相对路径: " + rawPath);
        }
        for (String segment : relative.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("文件路径不允许出现 ..: " + rawPath);
            }
        }
        return relative;
    }

    private void requireModelforGeneration() {
        if (llmClient == null) {
            throw AgentGenerationException.unavailable(
                    "生成能力不可用：未找到 LlmClient 实现（请确认 oryxos-provider 已装配）");
        }
        if (authorProvider == null || authorModel == null) {
            String missing = authorProvider == null && authorModel == null
                    ? "oryxos.author.provider 与 oryxos.author.model"
                    : authorProvider == null ? "oryxos.author.provider" : "oryxos.author.model";
            throw AgentGenerationException.unavailable(
                    "生成能力不可用：请配置 " + missing + "（当前 oryxos.author.provider="
                            + authorProvider + ", oryxos.author.model=" + authorModel + "）");
        }
    }

    /** 生成用的临时 Profile：只用于路由，不进注册表、不落盘。 */
    Profile authorProfile() {
        Profile profile = new Profile();
        profile.setName(AUTHOR_PROFILE_NAME);
        profile.setDescription("OryxOS Agent 配置生成器（内部）");
        Profile.ProviderConfig provider = new Profile.ProviderConfig();
        provider.setName(authorProvider);
        provider.setModel(authorModel);
        provider.setTemperature(0.2);
        profile.setProvider(provider);
        return profile;
    }

    String generationPrompt(String name, String description) {
        String tools = availableTools.isEmpty() ? "（当前没有可用工具，tools 段请省略）" : String.join(", ", availableTools);
        return """
                你是 OryxOS 的 Agent 配置工程师。根据用户需求，产出一个可直接运行的 Agent 定义。

                输出格式（严格遵守）：
                - 只输出文件内容，不要任何解释、不要致辞。
                - 每个文件以一行分隔符开头：=== FILE: <相对路径> ===
                - 至少要有一份 AGENT.md；需要补充背景时再加 REFERENCE.md。

                AGENT.md 的结构（Markdown + frontmatter）：
                ---
                description: "<一句话说明这个 Agent 干什么>"
                provider:
                  name: %s
                  model: %s
                tools:
                  - <工具名，只列真正需要的>
                ---

                # 角色
                <2~4 句：它是什么、为谁服务、说话口吻>

                ## 工作方式
                <3~5 条可执行的步骤或硬约束>

                硬约束：
                1. frontmatter 里禁止出现 skills: 字段。
                2. provider.name 必须是 %s，model 必须是 %s。
                3. tools 只允许从这些名字里选：%s
                4. 正文用中文，不要写"作为一个 AI 助手"这类空话，不要编造不存在的工具。
                5. 不要用 ``` 围栏包裹整个文件。

                用户需求：%s
                Agent 名：%s
                """.formatted(
                authorProvider, authorModel, authorProvider, authorModel, tools,
                description == null || description.isBlank() ? "（未提供，请按 Agent 名合理推断）" : description.strip(),
                name);
    }

    /**
     * 解析模型输出为 {@code 路径 → 内容}。
     *
     * <p>容错两条：完全没有分隔行时把整段当成 {@code AGENT.md}（小模型经常这样）；
     * 每段内容外围的 ``` 围栏会剥掉（它们在 Markdown 渲染里好看，在 frontmatter 里是硬错）。</p>
     */
    static Map<String, String> parseGeneratedFiles(String text) {
        Map<String, String> files = new LinkedHashMap<>();
        String current = null;
        StringBuilder buffer = new StringBuilder();

        for (String line : text.lines().toList()) {
            Matcher matcher = FILE_HEADER.matcher(line.strip());
            if (matcher.matches()) {
                if (current != null) {
                    files.put(current, cleanGeneratedContent(buffer.toString()));
                }
                current = matcher.group(1).strip().replace('\\', '/');
                buffer.setLength(0);
            } else if (current != null) {
                buffer.append(line).append('\n');
            }
        }
        if (current != null) {
            files.put(current, cleanGeneratedContent(buffer.toString()));
        }

        if (files.isEmpty()) {
            String body = cleanGeneratedContent(text);
            if (!body.isBlank()) {
                files.put(AgentLoader.MAIN_FILE, body);
            }
        }
        files.entrySet().removeIf(entry -> entry.getValue().isBlank());
        return files;
    }

    /** 去掉首尾空行与包裹用的代码围栏。 */
    static String cleanGeneratedContent(String raw) {
        List<String> lines = new ArrayList<>(raw.lines().toList());
        while (!lines.isEmpty() && lines.getFirst().isBlank()) {
            lines.removeFirst();
        }
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        if (lines.size() >= 2
                && lines.getFirst().strip().startsWith("```")
                && lines.getLast().strip().equals("```")) {
            lines.removeFirst();
            lines.removeLast();
        }
        return String.join("\n", lines) + "\n";
    }

    private void recordSkipped(String name, String reason) {
        skipped.removeIf(item -> name.equals(item.dirName()));
        skipped.add(new SkippedAgent(name, reason));
    }

    private void clearSkipped(String name) {
        skipped.removeIf(item -> name.equals(item.dirName()));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
