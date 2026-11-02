package io.oryxos.core.context;

import io.oryxos.core.profile.AgentLoader;
import io.oryxos.core.profile.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ContextLoader —— 负责加载注入 system prompt 的上下文（Bootstrap 文件 + SKILL.md）。
 *
 * <p>Bootstrap 与 SKILL 本质相同（都是注入 system prompt 的 markdown），只是来源不同，
 * 因此合并到同一个模块。SKILL.md 不是可执行的 Tool，故不放在 tool 模块。</p>
 *
 * <p><b>两条铁律</b>：</p>
 * <ol>
 *   <li><b>每次组装 prompt 都重新读文件、不缓存</b>——用户改完立即生效。</li>
 *   <li><b>Profile 显式引用的文件缺失要报错；默认 Bootstrap 缺失至少 WARN</b>——静默跳过会造成
 *       "人格悄悄丢了"这类最难查的软故障。</li>
 * </ol>
 */
public class ContextLoader {

    private static final Logger logger = LoggerFactory.getLogger(ContextLoader.class);

    /** 未显式配置 {@code bootstrap.files} 时按此顺序尝试加载；缺失只 WARN、不阻断。 */
    private static final List<String> DEFAULT_BOOTSTRAP_FILES = List.of("AGENTS.md", "SOUL.md", "USER.md");

    private static final String SECTION_SEPARATOR = "\n\n";

    private final Path workspace;

    /**
     * @param workspace 工作区根目录（即 {@code .oryxos} 目录）
     */
    public ContextLoader(Path workspace) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    /** identity + Bootstrap 文件内容。 */
    public String loadBootstrap(Profile profile) {
        List<String> sections = new ArrayList<>();
        addIfPresent(sections, profile.getIdentity());
        sections.addAll(bootstrapFileSections(profile));
        return join(sections);
    }

    /** identity + 正文（目录 Agent）/ Bootstrap 文件 + SKILL 文件，供 PromptBuilder 直接使用。 */
    public String loadSystemContext(Profile profile) {
        List<String> sections = new ArrayList<>();
        addIfPresent(sections, profile.getIdentity());
        sections.addAll(profile.isFromAgentDirectory()
                ? agentSections(profile)
                : bootstrapFileSections(profile));
        sections.addAll(skillSections(profile));
        return join(sections);
    }

    /**
     * 读取 {@code <workspace>/skills/<name>/SKILL.md}。
     *
     * @throws ContextLoadException 文件不存在（Profile 显式引用，不允许静默跳过）
     */
    public String loadSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            throw new ContextLoadException("技能名不能为空");
        }
        Path file = workspace.resolve("skills").resolve(skillName).resolve("SKILL.md");
        if (!Files.isRegularFile(file)) {
            throw new ContextLoadException(
                    "SKILL.md 不存在: " + file + "（Profile 显式引用了技能 '" + skillName + "'）");
        }
        return readFile(file);
    }

    /** 解析 {@code <workspace>/profiles/<name>.yaml} 为 Map（供 CLI 等读取原始配置）。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadProfileYaml(String profileName) {
        Path profileFile = workspace.resolve("profiles").resolve(profileName + ".yaml");
        if (!Files.isRegularFile(profileFile)) {
            throw new ContextLoadException("Profile 不存在: " + profileFile);
        }
        try (var reader = Files.newBufferedReader(profileFile)) {
            return new Yaml().load(reader);
        } catch (IOException e) {
            throw new ContextLoadException("Profile 读取失败: " + profileFile + "（" + e.getMessage() + "）", e);
        }
    }

    /** 工作区根目录（即 {@code .oryxos} 目录），供调用方拼别的路径时复用同一份基准。 */
    public Path workspace() {
        return workspace;
    }

    // ------------------------------------------------------------------

    /**
     * legacy Profile 的 Bootstrap 段：显式声明优先，否则尝试默认三件套。
     *
     * <p>两种模式的失败姿态刻意不同：<b>显式声明</b>的文件缺失要抛异常（你明确引用了它，
     * 悄悄不给等于让 Agent 少了关键设定却毫无提示）；<b>默认尝试</b>时缺失只告警
     * （大多数工作区并不会写这三份文件，报错会让"能跑"变成"跑不起来"）。</p>
     *
     * <p>与目录 Agent 的区别：只有 legacy 会走默认三件套，目录 Agent 见 {@link #agentSections}。</p>
     */
    private List<String> bootstrapFileSections(Profile profile) {
        List<String> explicitFiles = profile.bootstrapFiles();
        boolean explicitMode = !explicitFiles.isEmpty();
        List<String> targets = explicitMode ? explicitFiles : DEFAULT_BOOTSTRAP_FILES;

        List<String> sections = new ArrayList<>();
        for (String name : targets) {
            Path file = resolveWorkspacePath(name);
            if (!Files.isRegularFile(file)) {
                if (explicitMode) {
                    throw new ContextLoadException(
                            "Bootstrap 文件不存在: " + file + "（Profile 的 bootstrap.files 显式引用）");
                }
                logger.warn("默认 Bootstrap 文件缺失，跳过: {}", file);
                continue;
            }
            // 每次都重新读取，不缓存
            addIfPresent(sections, readFile(file));
        }
        return sections;
    }

    /**
     * 目录 Agent 的上下文段：正文（每次读盘）+ 资源目录说明 + 显式声明的 Bootstrap。
     *
     * <p><b>刻意不读工作区根的默认三件套</b>（{@code AGENTS.md/SOUL.md/USER.md}）：
     * 那三份是"这个人设"的全局设定，若每个目录 Agent 都继承，装 N 个 Agent 等于给 N 个 Agent
     * 都套上同一个人格，改一个还互相影响。</p>
     */
    private List<String> agentSections(Profile profile) {
        List<String> sections = new ArrayList<>();
        addIfPresent(sections, loadAgentBody(profile.getAgentDir()));
        addIfPresent(sections, resourceNote(profile.getAgentDir()));
        sections.addAll(agentBootstrapFiles(profile));
        return sections;
    }

    /**
     * 读取 {@code <agentDir>/AGENT.md} 的正文（剥掉 frontmatter）。
     *
     * <p>每次都重新读、不缓存：这是"改正文即时生效"的唯一实现方式。文件缺失时抛
     * {@link ContextLoadException} 而不是回退旧内容——静默沿用旧指令会让"改了没生效"
     * 变成更难查的问题。</p>
     */
    public String loadAgentBody(Path agentDir) {
        if (agentDir == null) {
            throw new ContextLoadException("目录 Agent 缺少 agentDir，无法定位正文");
        }
        Path file = agentDir.resolve(AgentLoader.MAIN_FILE);
        if (!Files.isRegularFile(file)) {
            throw new ContextLoadException("Agent 主文件不存在: " + file);
        }
        return AgentLoader.extractBody(readFile(file));
    }

    /**
     * 资源目录说明。
     *
     * <p>模型并不知道"自己在哪个目录"，而正文里天然会写 {@code scripts/x.py} 这种相对路径；
     * 不把绝对路径写进 prompt，脚本与参考文件就会按 JVM 的当前目录去找，必然找不到。</p>
     */
    private String resourceNote(Path agentDir) {
        Path base = agentDir.toAbsolutePath().normalize();
        return "## 你的资源目录\n"
                + base + "\n"
                + "- 脚本：用绝对路径执行，例如 `python " + base.resolve("scripts").resolve("xxx.py")
                + "`（只允许执行本目录 scripts/ 下的脚本）。\n"
                + "- 参考与子指令：用 read_file 读取 `" + base.resolve("REFERENCE.md")
                + "` 或 `" + base.resolve("skills") + "` 下的文件；用到才读，不要预先全读。\n"
                + "- 记忆：用 save_memory / recall_memory，工具会自动落到本 Agent 自己的记忆文件，不要直接读写。";
    }

    /** 目录 Agent 显式声明的 Bootstrap 文件：相对 {@code agentDir} 解析，缺失即报错。 */
    private List<String> agentBootstrapFiles(Profile profile) {
        List<String> explicitFiles = profile.bootstrapFiles();
        if (explicitFiles.isEmpty()) {
            return List.of();
        }
        List<String> sections = new ArrayList<>();
        for (String name : explicitFiles) {
            Path path = Path.of(name);
            Path file = path.isAbsolute() ? path : profile.getAgentDir().resolve(path);
            if (!Files.isRegularFile(file)) {
                throw new ContextLoadException(
                        "Bootstrap 文件不存在: " + file + "（Agent 的 bootstrap.files 显式引用）");
            }
            addIfPresent(sections, readFile(file));
        }
        return sections;
    }

    /**
     * 把 {@code Profile.skills} 展开成提示词段落（每轮<b>全量</b>注入）。
     *
     * <p>注意与目录里的 {@code skills/} 区分：这里是配置里声明的"必须一直知道的技能"，
     * 目录里的 {@code skills/*.md} 是"用到才 read_file 的资源"，两者同名不同义。
     * 正因为如此，{@code AgentLoader} 拒绝在 AGENT.md 里写 {@code skills:}。</p>
     *
     * <p>单个技能优先用它内联的 {@code prompt}；没内联就按约定读
     * {@code <workspace>/skills/<name>/SKILL.md}，缺失即报错（同"显式引用"的处理）。</p>
     */
    private List<String> skillSections(Profile profile) {
        List<String> sections = new ArrayList<>();
        if (profile.getSkills() == null) {
            return sections;
        }
        for (Profile.SkillConfig skill : profile.getSkills()) {
            if (skill == null || skill.getName() == null || skill.getName().isBlank()) {
                continue;
            }
            // 内联 prompt 优先；未内联则按约定读文件（缺失即报错）
            if (skill.getPrompt() != null && !skill.getPrompt().isBlank()) {
                addIfPresent(sections, skill.getPrompt());
            } else {
                addIfPresent(sections, loadSkill(skill.getName()));
            }
        }
        return sections;
    }

    /**
     * 解析 workspace 内的相对路径；绝对路径原样使用。
     *
     * <p>允许绝对路径是为了让 {@code bootstrap.files} 能引用工作区外的共享文件；
     * 代价是配置能指向任意位置——这是"配置由运维掌握"的既定信任模型，不是漏洞。</p>
     */
    private Path resolveWorkspacePath(String name) {
        Path path = Path.of(name);
        return path.isAbsolute() ? path : workspace.resolve(name);
    }

    /**
     * 读一个上下文文件，任何 IO 失败都转成 {@link ContextLoadException}。
     *
     * <p>不在这里降级为空串：上下文缺失属于"这个 Agent 的设定不完整"，
     * 空着继续跑会让模型在缺少约束的情况下自由发挥，比直接失败更难排查。</p>
     */
    private String readFile(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new ContextLoadException("上下文文件读取失败: " + file + "（" + e.getMessage() + "）", e);
        }
    }

    /**
     * 只在有内容时加入一段，避免出现连续空段（空段会让模型看到"这里好像少了点什么"）。
     */
    private static void addIfPresent(List<String> sections, String text) {
        if (text != null && !text.isBlank()) {
            sections.add(text.trim());
        }
    }

    /** 用统一分隔符拼接各段；分隔符只有一处定义，格式调整不用改每个调用点。 */
    private static String join(List<String> sections) {
        return String.join(SECTION_SEPARATOR, sections);
    }
}
