package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * AgentStore —— Agent 目录的"文件系统事实层"。
 *
 * <p>只回答"磁盘上有什么、怎么写才安全"，<b>不注册、不碰注册表、不碰调度</b>。
 * 把这一层单独抽出来的理由：注册表是内存里的真相，目录是磁盘上的真相，两者之间的每一条
 * 转换规则（先写还是先注册、失败怎么回滚）都属于编排，混在文件操作里必然写乱。</p>
 *
 * <p><b>三个必须守住的判断：</b></p>
 * <ol>
 *   <li><b>写 AGENT.md 必须原子替换</b>：监听器就在旁边看着，一次"先截断再写入"会让它读到空文件，
 *       把一个好好的 Agent 注销掉。所以一律走临时文件 + {@code ATOMIC_MOVE}。</li>
 *   <li><b>删除是归档，不是消失</b>：{@code DELETE /agents/{name}} 的语义是"下线"，不是"抹掉"。
 *       整个目录移进 {@code archive/<name>-<时间戳>}，审计表里的 sessionId / runId 才还能对得上人。</li>
 *   <li><b>同名归档永不覆盖</b>：同一秒内删两次（或删了又建再删）是完全可能的，追加序号即可。</li>
 * </ol>
 *
 * <p>目录布局（与 29 节一致，本节只补齐创建侧）：</p>
 * <pre>
 * .oryxos/
 *   agents/&lt;name&gt;/AGENT.md          # 配置 + 指令（唯一入口文件）
 *   agents/&lt;name&gt;/REFERENCE.md      # 可按需读的参考资料
 *   agents/&lt;name&gt;/skills/           # 可按需读的技能说明（不是 Profile.skills）
 *   agents/&lt;name&gt;/scripts/          # 可执行脚本（信任边界见 AgentLoader 的启动告警）
 *   agents/&lt;name&gt;/memory/MEMORY.md  # 该 Agent 独立的长期记忆（由 MemoryLocator 决定，不在这里建）
 *   archive/&lt;name&gt;-&lt;yyyyMMdd-HHmmss&gt;/
 * </pre>
 */
public class AgentStore {

    private static final Logger logger = LoggerFactory.getLogger(AgentStore.class);

    /** 归档目录名的时间戳格式：可排序、可读、Windows 文件名安全（不含冒号）。 */
    private static final DateTimeFormatter ARCHIVE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 新建 Agent 时的模板文件名。 */
    public static final String REFERENCE_FILE = "REFERENCE.md";
    public static final String SCRIPTS_DIR = "scripts";
    public static final String SKILLS_DIR = "skills";

    private final Path workspaceRoot;
    private final Path agentsDir;
    private final Path archiveDir;

    /** 模板里预填的 provider / model（可为空：空则不写 provider 段，创建时会明确报错）。 */
    private final String defaultProvider;
    private final String defaultModel;

    /**
     * @param workspaceRoot {@code .oryxos/} 根目录
     * @param defaultProvider 新建 Agent 模板默认 provider（取全局层第一个已声明的 provider）
     * @param defaultModel    新建 Agent 模板默认 model（可空：不写则由 ChatModel 默认值决定）
     */
    public AgentStore(Path workspaceRoot, String defaultProvider, String defaultModel) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.agentsDir = this.workspaceRoot.resolve("agents");
        this.archiveDir = this.workspaceRoot.resolve("archive");
        this.defaultProvider = trimToNull(defaultProvider);
        this.defaultModel = trimToNull(defaultModel);
    }

    // ------------------------------------------------------------------
    // 位置
    // ------------------------------------------------------------------

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path agentsDir() {
        return agentsDir;
    }

    public Path archiveDir() {
        return archiveDir;
    }

    /** {@code <workspace>/agents/<name>}（绝对、已归一化）。名字合法性由调用方先行校验。 */
    public Path agentDir(String name) {
        return agentsDir.resolve(name).toAbsolutePath().normalize();
    }

    /** 该名字在磁盘上是否已是一个 Agent 目录。 */
    public boolean exists(String name) {
        return name != null && !name.isBlank() && Files.isDirectory(agentDir(name));
    }

    /** 模板使用的默认 provider；为空表示这个实例没声明任何 provider。 */
    public String defaultProvider() {
        return defaultProvider;
    }

    /** 模板使用的默认 model；为空表示不写 {@code model} 字段。 */
    public String defaultModel() {
        return defaultModel;
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    /**
     * 脚手架出一个完整的 Agent 目录。
     *
     * <p>为什么是"整目录"而不是只写 {@code AGENT.md}：管理台上"新建"之后紧接着就是
     * "我该把资料放哪"。把 {@code REFERENCE.md} 与两个空目录先摆好，用户不用去猜布局；
     * 而空的 {@code scripts/} 也顺带把"这里有信任边界"这件事摆到明面上。</p>
     *
     * <p>不写 {@code memory/MEMORY.md}：那个文件由记忆门面在首次写入时按自己的骨架创建，
     * 这里抢先建一个空文件只会让记忆模块读到"没有区块的坏文件"。</p>
     *
     * @return 新目录绝对路径
     * @throws IOException 目录或文件创建失败（调用方负责回滚）
     */
    public Path createScaffold(String name, String description) throws IOException {
        Path dir = agentDir(name);
        Files.createDirectories(dir.resolve(SCRIPTS_DIR));
        Files.createDirectories(dir.resolve(SKILLS_DIR));

        writeAtomically(dir.resolve(AgentLoader.MAIN_FILE), agentTemplate(name, description));
        Path reference = dir.resolve(REFERENCE_FILE);
        if (Files.notExists(reference)) {
            writeAtomically(reference, referenceTemplate(name, description));
        }
        logger.info("[Agent] 已创建脚手架: name={}, dir={}", name, dir);
        return dir;
    }

    /** {@code AGENT.md} 模板：能直接通过 29 节的解析与校验（provider 已声明时）。 */
    String agentTemplate(String name, String description) {
        String desc = description == null || description.isBlank()
                ? "（待补充：这个 Agent 负责什么）"
                : description.strip();

        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("description: ").append(quoteYaml(desc)).append('\n');
        if (defaultProvider != null) {
            sb.append("provider:\n");
            sb.append("  name: ").append(defaultProvider).append('\n');
            if (defaultModel != null) {
                sb.append("  model: ").append(defaultModel).append('\n');
            }
            sb.append("  temperature: 0.3\n");
        }
        sb.append("# 这一段是「权限」不是「话术」：没列出的工具，模型根本看不见。\n");
        sb.append("# 整段删掉 = 不限制（模型能看到全部工具，含文件与命令），请按需收紧。\n");
        sb.append("tools:\n");
        sb.append("  - read_file\n");
        sb.append("---\n\n");
        sb.append("# 角色\n\n");
        sb.append(desc).append("\n\n");
        sb.append("## 工作方式\n\n");
        sb.append("1. 先判断输入是否足够；缺关键信息时只问一句，不要连问三个问题。\n");
        sb.append("2. 需要事实与口径时先读 `REFERENCE.md`；需要固定流程时再看 `skills/` 下的说明。\n");
        sb.append("3. 结论先行，写清楚依据，不复述过程。\n");
        sb.append("4. 不确定的事情直接说不确定，不要编。\n");
        return sb.toString();
    }

    /** {@code REFERENCE.md} 模板：告诉用户"该往这里写什么"。 */
    String referenceTemplate(String name, String description) {
        return """
                # %s 参考资料

                把「跑这个 Agent 必须知道的事实」写在这里。它是按需读取的资源，
                不会每轮注入 prompt——所以可以长，但要有结构。

                ## 背景
                %s

                ## 输入
                - 什么时候会被叫醒（人推 / 定时 / API）
                - 每次能拿到哪些字段

                ## 输出
                - 期望的格式（Markdown / JSON / 一句话）
                - 不该出现的内容

                ## 边界
                - 明确不做的事
                - 需要人介入的情况
                """.formatted(name, description == null || description.isBlank() ? "（待补充）" : description.strip());
    }

    // ------------------------------------------------------------------
    // 写入与归档
    // ------------------------------------------------------------------

    /**
     * 原子写入：同目录临时文件 → 替换目标。
     *
     * <p>临时文件必须与目标<b>同目录</b>：跨盘/跨文件系统的 move 不是原子操作，
     * 放到系统临时目录就等于把"安全写"换成了"看起来像安全写"。</p>
     *
     * <p>临时文件名带 {@code .tmp-} 前缀后缀，监听器按文件名过滤，不会被它触发一次多余的重注册。</p>
     */
    public void writeAtomically(Path file, String content) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("目标文件没有父目录: " + absolute);
        }
        Files.createDirectories(parent);

        Path tmp = parent.resolve(absolute.getFileName() + ".tmp-" + System.nanoTime());
        try {
            Files.writeString(tmp, content == null ? "" : content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, absolute,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 某些网络盘/容器挂载不支持原子移动：退化为替换，仍然比"先截断再写"安全
                logger.debug("[Agent] 目标文件系统不支持原子移动，退化为普通替换: {}", absolute);
                Files.move(tmp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * 归档一个 Agent 目录：{@code agents/<name>} → {@code archive/<name>-<yyyyMMdd-HHmmss>}。
     *
     * @return 归档后的目录路径
     * @throws IOException 目录不存在或移动失败
     */
    public Path archive(String name) throws IOException {
        Path source = agentDir(name);
        if (!Files.isDirectory(source)) {
            throw new IOException("待归档的 Agent 目录不存在: " + source);
        }
        Files.createDirectories(archiveDir);

        String base = name + "-" + LocalDateTime.now().format(ARCHIVE_TIMESTAMP);
        Path target = archiveDir.resolve(base);
        int seq = 2;
        while (Files.exists(target)) {
            target = archiveDir.resolve(base + "-" + seq);
            seq++;
        }

        Files.move(source, target);
        logger.info("[Agent] 已归档: name={}, from={}, to={}", name, source, target);
        return target;
    }

    /**
     * 递归删除一个目录（<b>只用于回滚</b>：创建到一半失败时把半成品抹掉）。
     *
     * <p>正常删除走 {@link #archive(String)}。这个方法单独存在，是因为"回滚"与"下线"是
     * 两件不同的事：前者要的是"当作没发生过"，后者要的是"留下证据"。</p>
     */
    public void deleteTree(Path dir) throws IOException {
        if (dir == null || Files.notExists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        logger.warn("[Agent] 已回滚删除目录: {}", dir);
    }

    // ------------------------------------------------------------------

    /** YAML 标量：双引号包裹并转义，避免描述里的冒号、引号把 frontmatter 打散。 */
    static String quoteYaml(String value) {
        String flat = value.replace("\r", " ").replace("\n", " ").strip();
        return "\"" + flat.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
