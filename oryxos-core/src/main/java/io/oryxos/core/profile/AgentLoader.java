package io.oryxos.core.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * AgentLoader —— 把 {@code <workspace>/agents/<name>/} 目录解析成底座认识的 {@link Profile}。
 *
 * <p>这是"插件化"的机制本体，三件事：<b>解析</b>（frontmatter + 正文 + 资源位置）→
 * <b>派生</b>（映射成 {@link Profile} 并挂上 {@code agentDir}）→ <b>注册</b>（进 {@link ProfileRegistry}）。</p>
 *
 * <p><b>四个必须守住的判断：</b></p>
 * <ol>
 *   <li><b>坏目录只跳过自己</b>：一个目录的配置错不该让整个实例起不来（与 legacy 的"坏文件跳过"一致），
 *       但**必须**进 {@link #skippedAgents()}——只写日志等于"它悄悄消失了"。</li>
 *   <li><b>未知字段是硬错</b>：Jackson 默认忽略未知字段，那会让 {@code zone: Asia/Shanghai}
 *       这种拼写错误变成"配了没生效"。这里显式打开 {@code FAIL_ON_UNKNOWN_PROPERTIES}。</li>
 *   <li><b>{@code skills:} 是硬错</b>：代码里的 {@code Profile.skills} 是**每轮全量注入** prompt 的，
 *       而目录里的 {@code skills/} 是"按需读"的资源。两者同名不同义，映射过去会让渐进式披露直接失效。</li>
 *   <li><b>名字取目录名</b>：目录名同时是注册键、会话维度与 URL 片段，必须以文件系统为准；
 *       frontmatter 里写 {@code name} 只用来做一致性提示。</li>
 * </ol>
 */
public class AgentLoader {

    private static final Logger logger = LoggerFactory.getLogger(AgentLoader.class);

    /** 主文件名（固定，不做多形态兼容：多一种写法就多一种"为什么没生效"）。 */
    public static final String MAIN_FILE = "AGENT.md";

    /** 目录名允许的字符集：它会进注册表键、session 表、URL 与 CLI 参数。 */
    private static final Pattern VALID_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private static final String FRONTMATTER_FENCE = "---";

    private static final Pattern UNKNOWN_FIELD =
            Pattern.compile("Unrecognized field \"([^\"]+)\"");

    private final Path agentsDir;
    private final ProfileValidator validator;
    private final ProfileRegistry registry;

    /** 本次扫描被跳过的目录；每次 {@link #loadAll()} 前重置。 */
    private final List<SkippedAgent> skipped = new CopyOnWriteArrayList<>();

    /** 本次扫描解析成功的定义（供诊断与测试；注册表才是运行时真相）。 */
    private final List<AgentDefinition> definitions = new CopyOnWriteArrayList<>();

    /** 严格模式：未知字段直接失败，而不是被静默忽略。 */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .setPropertyNamingStrategy(
                            com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
                    .configure(
                            com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            true);

    /**
     * @param agentsDir          Agent 目录区（不存在视为"没有目录 Agent"）
     * @param availableProviders 全局层已声明的 provider 名（与 legacy 共用同一个校验器）
     * @param registry           目标注册表；必须是 legacy 用的**同一个实例**，否则同名覆盖与可见性都无从谈起
     */
    public AgentLoader(Path agentsDir, Collection<String> availableProviders, ProfileRegistry registry) {
        this.agentsDir = Objects.requireNonNull(agentsDir, "agentsDir");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.validator = new ProfileValidator(availableProviders);
    }

    /**
     * 扫描并注册。坏目录跳过、不抛异常。
     *
     * @return 本次成功注册的 Agent 数
     */
    public int loadAll() {
        skipped.clear();
        definitions.clear();

        if (!Files.isDirectory(agentsDir)) {
            logger.info("[Agent] Agent 目录不存在，跳过扫描: {}", agentsDir);
            return 0;
        }

        List<Path> dirs;
        try (Stream<Path> stream = Files.list(agentsDir)) {
            dirs = stream.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            logger.error("[Agent] 扫描 Agent 目录失败: {}", agentsDir, e);
            return 0;
        }

        int loaded = 0;
        for (Path dir : dirs) {
            if (loadOne(dir)) {
                loaded++;
            }
        }

        logger.info("[Agent] 目录 Agent 扫描完成：成功 {} 个，跳过 {} 个（目录: {}）",
                loaded, skipped.size(), agentsDir);
        for (SkippedAgent item : skipped) {
            logger.warn("[Agent] 已跳过目录 Agent: dir={}, reason={}", item.dirName(), item.reason());
        }
        warnIfScriptsPresent();
        return loaded;
    }

    /** 加载单个目录；返回 false 表示被跳过（原因已进 {@link #skippedAgents()}）。 */
    boolean loadOne(Path dir) {
        String name = dir.getFileName().toString();

        Derived derived;
        try {
            derived = derive(dir);
        } catch (AgentDefinitionException e) {
            return skip(name, e.getMessage());
        }

        Profile profile = derived.profile();

        // agents 优先：同名时 legacy 的那份不再注册（它的 schedules 也随之不生效）
        if (registry.exists(name)) {
            skipped.add(new SkippedAgent(name,
                    "名字冲突：agents/" + name + " 覆盖了已注册的同名 legacy Profile，legacy 版本不再生效"));
            logger.warn("[Agent] 目录 Agent [{}] 覆盖同名 legacy Profile（以目录为准）", name);
        }

        registry.register(profile);
        definitions.add(derived.definition());
        logger.info("[Agent] 已注册目录 Agent: name={}, provider={}, bodyChars={}, dir={}",
                name,
                profile.getProvider() == null ? "<无>" : profile.getProvider().getName(),
                derived.definition().body() == null ? 0 : derived.definition().body().length(),
                dir.toAbsolutePath().normalize());

        if (derived.definition().hasEmptyBody()) {
            logger.warn("[Agent] 目录 Agent [{}] 的正文为空：只有配置没有指令，模型不知道要做什么", name);
        }
        return true;
    }

    /**
     * 解析一个 Agent 目录并派生 Profile —— <b>不注册、不改任何内部状态</b>。
     *
     * <p>这是运行时（API 建 Agent、监听器发现新目录）与启动扫描共用的唯一解析入口：
     * 只要"解析 + 派生 + 校验"只有一份实现，两条录入路径就不可能出现"API 建出来的能跑、
     * 手工拷进去的跑不起来"这种漂移。</p>
     *
     * <p>刻意不 register：调用方要先决定"旧注册要不要先撤掉"（见 {@code AgentLifecycleService}），
     * 把注册塞进解析里等于把顺序问题藏进一个看似无害的方法。</p>
     *
     * @throws AgentDefinitionException 目录名非法、缺 {@code AGENT.md}、frontmatter 非法、provider 未声明
     */
    public Derived derive(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        if (!isValidName(name)) {
            throw new AgentDefinitionException(
                    "目录名不合法（只允许小写字母、数字与中划线，且以字母或数字开头）: " + name);
        }

        Path mainFile = dir.resolve(MAIN_FILE);
        if (!Files.isRegularFile(mainFile)) {
            throw new AgentDefinitionException("缺 " + MAIN_FILE + "（目录: " + name + "）");
        }

        String raw;
        try {
            raw = stripBom(Files.readString(mainFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AgentDefinitionException(MAIN_FILE + " 读取失败: " + e.getMessage(), e);
        }
        return deriveFromText(name, dir, raw);
    }

    /**
     * 用一段 markdown 文本派生 Profile，<b>完全不碰磁盘</b>。
     *
     * <p>三个使用场景：<b>更新</b>（写盘前先校验，非法就一个字节都不写）、
     * <b>生成</b>（模型吐出来的内容先验证能不能被解析）、<b>单文件保存</b>（同上）。</p>
     *
     * @param name      Agent 名（= 目录名）
     * @param dir       Agent 目录（用于拼资源位置；不需要已存在）
     * @param markdown  AGENT.md 全文
     * @throws AgentDefinitionException 名字非法、frontmatter 非法、provider 未声明
     */
    public Derived deriveFromText(String name, Path dir, String markdown) {
        if (!isValidName(name)) {
            throw new AgentDefinitionException(
                    "Agent 名不合法（只允许小写字母、数字与中划线，且以字母或数字开头）: " + name);
        }

        AgentDefinition definition;
        try {
            definition = parseRaw(dir, name, stripBom(markdown == null ? "" : markdown));
        } catch (FrontmatterException e) {
            throw new AgentDefinitionException(e.getMessage());
        }

        Profile profile;
        try {
            profile = deriveProfile(definition);
        } catch (FrontmatterException e) {
            throw new AgentDefinitionException(e.getMessage());
        }

        try {
            validator.validate(profile);
        } catch (ProfileValidationException e) {
            throw new AgentDefinitionException(e.getMessage());
        }

        return new Derived(profile, definition);
    }

    /** 目录名是否合法（同时是注册键、会话维度与 URL 片段）。 */
    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 把一段 markdown 拆成"frontmatter 配置 + 正文 + 资源位置"。
     *
     * <p>这里只管**解析**，不做任何校验（provider 是否可用、字段是否合法都在后面）。
     * 把两者分开的理由：读取失败（文件没了、编码坏了）与配置非法（写错了字段）是两类问题，
     * 混在一起写，报错信息就只能给一句含糊的"加载失败"。</p>
     *
     * @param dir 目录（绝对路径，用于拼资源位置；不需要真实存在）
     * @param name 目录名（= Agent 名）
     * @param raw  AGENT.md 全文（已去 BOM）
     * @throws FrontmatterException frontmatter 未闭合、YAML 非法、含 {@code skills:}
     */
    private AgentDefinition parseRaw(Path dir, String name, String raw) {
        Parsed parsed = splitFrontmatter(raw);

        Map<String, Object> frontmatter = parseYaml(parsed.yaml());

        Object identity = frontmatter.get("identity");
        if (identity instanceof Map<?, ?> obj) {
            frontmatter.put("identity", flattenIdentity(obj));
        }

        // 目录里的 skills/ 是"按需读"的资源，不是每轮注入的 Profile.skills
        if (frontmatter.containsKey("skills")) {
            throw new FrontmatterException(
                    "AGENT.md 不支持 skills:（目录里的 skills/ 是可按需 read_file 的资源，"
                            + "而 Profile.skills 会被每轮注入 prompt）");
        }

        return new AgentDefinition(
                name,
                dir.toAbsolutePath().normalize(),
                frontmatter,
                parsed.body(),
                dir.resolve("REFERENCE.md"),
                dir.resolve("skills"),
                dir.resolve("scripts"));
    }

    /** frontmatter 主体解析：SnakeYAML 读成 Map，再做 {@code ${ENV}} 替换。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object loaded;
        try {
            loaded = new Yaml().load(yaml);
        } catch (RuntimeException e) {
            throw new FrontmatterException("frontmatter YAML 格式非法: " + e.getMessage());
        }
        if (loaded == null) {
            return new LinkedHashMap<>();
        }
        if (!(loaded instanceof Map)) {
            throw new FrontmatterException(
                    "frontmatter 必须是映射（key: value），实际为 "
                            + loaded.getClass().getSimpleName());
        }
        Object resolved = ProfileLoader.resolvePlaceholders(loaded);
        return new LinkedHashMap<>((Map<String, Object>) resolved);
    }

    /**
     * 把 frontmatter 映射成 {@link Profile} 并挂上目录。
     *
     * @throws FrontmatterException 字段非法 / 含未知字段
     */
    Profile deriveProfile(AgentDefinition definition) {
        Profile profile;
        try {
            profile = objectMapper.convertValue(definition.frontmatter(), Profile.class);
        } catch (IllegalArgumentException e) {
            throw new FrontmatterException(describeMappingError(e));
        }
        if (profile == null) {
            throw new FrontmatterException("frontmatter 无法映射为 Profile（内容为空？）");
        }

        profile.setName(definition.name());
        profile.setAgentDir(definition.dir());

        Object declared = definition.frontmatter().get("name");
        if (declared != null && !definition.name().equals(String.valueOf(declared))) {
            logger.warn("[Agent] 目录 Agent [{}] 的 frontmatter.name={} 与目录名不一致，以目录名为准",
                    definition.name(), declared);
        }
        return profile;
    }

    /**
     * 把未知字段说清楚。
     *
     * <p>Jackson 的原始消息里带一堆类名，对配置作者毫无意义；这里只捞字段名，并顺带提示
     * "是不是把 timezone 写成了 zone"这类最常见的手滑。</p>
     */
    private static String describeMappingError(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        Matcher matcher = UNKNOWN_FIELD.matcher(message);
        if (matcher.find()) {
            String field = matcher.group(1);
            String hint = "zone".equals(field) ? "（时间区字段名是 timezone）" : "";
            return "frontmatter 含未知字段 '" + field + "'" + hint;
        }
        return "frontmatter 字段非法: " + message;
    }

    /**
     * 去掉开头的 UTF-8 BOM。
     *
     * <p>Windows 记事本 / PowerShell 写出的 UTF-8 常带 BOM，它会让首行的 {@code ---} 变成
     * {@code \uFEFF---}，于是整篇被当成正文、frontmatter 全部丢失——表现为"配置写了却完全不生效"。</p>
     */
    static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    /** 兼容 {@code identity: {agent_name, prompt}} 的对象写法：拼成一段文本。 */
    private static String flattenIdentity(Map<?, ?> identity) {
        Object agentName = identity.get("agent_name");
        Object prompt = identity.get("prompt");
        String namePart = agentName == null ? null : String.valueOf(agentName).trim();
        String promptPart = prompt == null ? null : String.valueOf(prompt).trim();
        if (namePart == null || namePart.isEmpty()) {
            return promptPart == null ? "" : promptPart;
        }
        return promptPart == null || promptPart.isEmpty() ? namePart : namePart + "\n" + promptPart;
    }

    /**
     * 取出主文件的正文（剥掉 frontmatter 与 BOM）。供 {@code ContextLoader} 每轮读盘复用，
     * 保证"正文的解析规则只有一份"。
     */
    public static String extractBody(String rawText) {
        if (rawText == null) {
            return "";
        }
        return splitFrontmatter(stripBom(rawText)).body();
    }

    /** 拆 frontmatter 与正文；没有 frontmatter 时整篇算正文。 */
    static Parsed splitFrontmatter(String text) {
        List<String> lines = new ArrayList<>(text.lines().toList());
        int first = 0;
        while (first < lines.size() && lines.get(first).isBlank()) {
            first++;
        }
        if (first >= lines.size() || !FRONTMATTER_FENCE.equals(lines.get(first).strip())) {
            return new Parsed(null, text.strip());
        }
        for (int i = first + 1; i < lines.size(); i++) {
            if (FRONTMATTER_FENCE.equals(lines.get(i).strip())) {
                String yaml = String.join("\n", lines.subList(first + 1, i));
                String body = String.join("\n", lines.subList(i + 1, lines.size())).strip();
                return new Parsed(yaml, body);
            }
        }
        throw new FrontmatterException("frontmatter 未闭合（缺少第二行 ---）");
    }

    // ------------------------------------------------------------------

    /**
     * 带脚本的 Agent 会让"装 Agent"变成"执行代码"，这条边界必须在启动时就喊出来。
     *
     * <p>本项目的沙箱只能约束"用哪个解释器、跑哪个目录的脚本"；脚本**内部**做什么
     * （读任意文件、发起任意网络请求、绕过 HTTP 工具的域名白名单）不归我们管。
     * 这个结论写在代码注释里没人会看到，所以每次启动都打一遍。</p>
     */
    private void warnIfScriptsPresent() {
        long withScripts = definitions.stream()
                .filter(definition -> Files.isDirectory(definition.scriptsDir()))
                .count();
        if (withScripts == 0) {
            return;
        }
        logger.warn("================================================================");
        logger.warn("[Agent] 有 {} 个目录 Agent 自带 scripts/，其脚本会由本机解释器执行", withScripts);
        logger.warn("[Agent] 信任边界：脚本内容不受本项目限制——可读写工作区文件、可发起任意网络请求，");
        logger.warn("[Agent] 且会绕过内置 HTTP 工具的域名白名单。装一个带脚本的 Agent = 信任它的作者。");
        logger.warn("================================================================");
    }

    /**
     * 记一条"被跳过的目录"并返回 {@code false}，让各校验分支保持单行表达。
     *
     * <p>同时写日志与清单：日志给当场看的人，清单给后面要看"为什么它没出现"的人
     * （30 节的接口会把它透到页面上）。两者缺一，都会让"我丢了个目录它却没生效"变成猜谜。</p>
     *
     * @param dirName 目录名（也是它本应成为的 Agent 名）
     * @param reason  可读原因（必须能指到具体问题，不能只写"加载失败"）
     * @return 恒为 {@code false}，供 {@code return skip(...)} 使用
     */
    private boolean skip(String dirName, String reason) {
        skipped.add(new SkippedAgent(dirName, reason));
        logger.error("[Agent] 目录 Agent 加载失败，已跳过: dir={}, reason={}", dirName, reason);
        return false;
    }

    /** 本次扫描被跳过的目录（只读快照）。 */
    public List<SkippedAgent> skippedAgents() {
        return List.copyOf(skipped);
    }

    /** 本次扫描解析成功的定义（只读快照）。 */
    public List<AgentDefinition> definitions() {
        return List.copyOf(definitions);
    }

    public Path agentsDir() {
        return agentsDir;
    }

    /**
     * 一次解析的完整产物：底座认识的 {@link Profile} + 目录视角的 {@link AgentDefinition}。
     *
     * <p>两者都要：注册表只吃 Profile，而"正文有没有写、资源目录在哪"只有定义知道。</p>
     */
    public record Derived(Profile profile, AgentDefinition definition) {
    }

    /** frontmatter 与正文的拆分结果。 */
    record Parsed(String yaml, String body) {
    }

    /** 目录解析阶段的失败（统一转成"跳过 + 原因"）。 */
    static class FrontmatterException extends RuntimeException {
        FrontmatterException(String message) {
            super(message);
        }
    }
}
