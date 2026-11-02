package io.oryxos.tool.sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 应用层白名单校验——核心阶段唯一的 {@link Sandbox} 实现。
 *
 * <p>三个维度各自独立：文件类校验真实路径、命令类校验首个 token、HTTP 类校验域名。
 * <b>空清单即拒绝</b>——默认方向是收紧的。</p>
 *
 * <p><b>三个校验方法都是 private</b>：外部只看得到 {@link #enforce(SandboxAction)} 这一个入口。
 * 它们的划分方式是本实现内部的组织自由，不是接口契约的一部分；一旦公开，
 * 上层就会按"白名单"的方式调用，接口立刻被第一档带偏。</p>
 *
 * <p><b>关于"关闭校验"</b>：{@link #WhitelistSandbox(List, List, List, boolean)} 的第四个参数
 * 是唯一的关闭入口。关闭后三个维度直接返回，不做任何判断。这个能力只服务于本地开发与调试；
 * 默认关闭值是 {@code false}（即启用），由装配层根据配置决定，并在关闭时打出醒目告警。</p>
 *
 * <p><b>这一档的局限（诚实标注）</b>：它只是劝阻，不是关押。拦的是"想到要拦的东西"，
 * 挡不住蓄意的命令拼接、编码绕过或未列入的危险命令。要跑完全不可信代码，
 * 需要扩展阶段的容器 / microVM 实现——届时只需新增一个 {@code implements Sandbox} 的类，
 * 四个调用方一行都不用改。</p>
 */
public class WhitelistSandbox implements Sandbox {

    /** 未显式配置解释器白名单时的默认值（Windows 常用 python / py，类 Unix 用 python3 / bash）。 */
    public static final List<String> DEFAULT_INTERPRETERS =
            List.of("python", "python3", "py", "bash", "sh", "node");

    /**
     * 目录 Agent 场景下禁止出现在命令里的字符：{@code cmd.exe /c} 会把它们解释成
     * 串联 / 重定向 / 变量展开，一次"只跑一个脚本"的限制会被立刻绕过。
     */
    private static final String FORBIDDEN_COMMAND_CHARS = "&|;<>^`%\n\r";

    private final List<String> allowedPaths;
    private final Set<String> allowedCommands;
    private final List<Pattern> allowedDomains;
    private final Set<String> allowedInterpreters;
    private final boolean enabled;

    /** 启用校验（默认行为）。 */
    public WhitelistSandbox(List<String> allowedPaths, List<String> allowedCommands, List<String> allowedDomains) {
        this(allowedPaths, allowedCommands, allowedDomains, true);
    }

    public WhitelistSandbox(List<String> allowedPaths,
                            List<String> allowedCommands,
                            List<String> allowedDomains,
                            boolean enabled) {
        this(allowedPaths, allowedCommands, allowedDomains, DEFAULT_INTERPRETERS, enabled);
    }

    /**
     * @param allowedInterpreters 目录 Agent 允许使用的解释器（按可执行文件名比较，忽略大小写与 .exe）
     * @param enabled             {@code false} 表示关闭全部维度校验（仅限本地开发与调试）；
     *                            调用方必须显式传入，且应在关闭时输出醒目告警
     */
    public WhitelistSandbox(List<String> allowedPaths,
                           List<String> allowedCommands,
                           List<String> allowedDomains,
                           List<String> allowedInterpreters,
                           boolean enabled) {
        this.allowedPaths = List.copyOf(allowedPaths);
        this.allowedCommands = Set.copyOf(allowedCommands);
        this.allowedDomains = allowedDomains.stream()
                .map(d -> Pattern.compile(globToRegex(d), Pattern.CASE_INSENSITIVE))
                .toList();
        this.allowedInterpreters = allowedInterpreters == null || allowedInterpreters.isEmpty()
                ? Set.of()
                : allowedInterpreters.stream()
                        .map(WhitelistSandbox::normalizeInterpreter)
                        .collect(Collectors.toUnmodifiableSet());
        this.enabled = enabled;
    }

    /** 当前是否启用校验；供装配层与测试观察实际状态。 */
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void enforce(SandboxAction action) {
        // 动作缺失属于调用方缺陷：一律按拒绝处理，绝不放行，也绝不把 NPE 冒给工具层
        if (action == null || action.type() == null) {
            throw new SandboxViolationException("沙箱动作缺少类型，拒绝执行");
        }
        String target = action.target();
        if (target == null || target.isBlank()) {
            throw new SandboxViolationException("沙箱动作目标为空，拒绝执行（动作类型: " + action.type() + "）");
        }
        if (!enabled) {
            return;
        }
        switch (action.type()) {
            // 目录 Agent：文件访问收窄到它自己的目录，命令收窄到"解释器 + 本目录 scripts/"
            case FILE_ACCESS -> {
                if (action.hasAgentDir()) {
                    checkAgentFilePath(target, action.agentDir());
                } else {
                    checkFilePath(target);
                }
            }
            case SHELL_EXEC -> {
                if (action.hasAgentDir()) {
                    checkAgentScript(target, action.agentDir());
                } else {
                    checkShellCommand(target);
                }
            }
            case HTTP_REQUEST -> checkHttpUrl(target);
        }
    }

    /**
     * 路径校验：按<b>真实路径</b>比对白名单。
     *
     * <p>为什么必须解析真实路径、而不能只做字面归一化：{@code Path.normalize()} 是纯字面运算，
     * 不访问文件系统、不解析符号链接。若只做字面比对，
     * {@code 白名单目录/link/密码文件}（其中 {@code link} 是指向白名单外目录的符号链接）
     * 会因"字面上落在白名单内"而被放行，随后真正读到的却是白名单外的文件——白名单形同虚设。</p>
     *
     * <p>目标还不存在时（例如写入一个新文件）无法直接解析真实路径，改为解析
     * <b>最近的已存在祖先</b>的真实路径，再拼回剩余段。这样既能覆盖"新建文件"，
     * 也能识别中间层已有的符号链接。</p>
     *
     * <p>解析失败（权限、异常）时退回字面绝对路径：宁可保守地按字面判断，
     * 也不因为解析错误而放行。</p>
     */
    private void checkFilePath(String path) {
        Path target = realPathOf(Paths.get(path));
        boolean allowed = allowedPaths.stream()
                .map(base -> realPathOf(Paths.get(base)))
                .anyMatch(target::startsWith);
        if (!allowed) {
            throw new SandboxViolationException("文件路径不在白名单内: " + path);
        }
    }

    /**
     * 解析真实路径；目标不存在时退回"最近的已存在祖先的真实路径 + 剩余段"。
     *
     * <p>白名单条目与待校验路径<b>都</b>走这条路径，保证两侧口径一致——
     * 否则配置里写了符号链接目录时，会出现"配了却匹配不上"的怪现象。</p>
     */
    private static Path realPathOf(Path candidate) {
        Path absolute = candidate.normalize().toAbsolutePath();
        for (Path existing = absolute; existing != null; existing = existing.getParent()) {
            if (!Files.exists(existing)) {
                continue;
            }
            try {
                Path real = existing.toRealPath();
                return existing.equals(absolute) ? real : real.resolve(existing.relativize(absolute));
            } catch (IOException e) {
                return absolute;
            }
        }
        return absolute;
    }

    /**
     * 拆出命令首个 token 比对白名单（精确匹配，大小写敏感）。
     *
     * <p>前导空白由 {@code trim} 消掉；大小写变体（{@code ECHO}）按"不在白名单内"拒绝——
     * 放宽匹配等于扩大白名单，方向与 fail-closed 相反。</p>
     */
    private void checkShellCommand(String command) {
        String firstToken = command.trim().split("\\s+")[0];
        if (!allowedCommands.contains(firstToken)) {
            throw new SandboxViolationException("Shell 命令不在白名单内: " + firstToken);
        }
    }

    /**
     * 解析 host 后做通配匹配。
     *
     * <p>通配转正则时点号被转义，因此 {@code *.example.com} 天然要求标签边界，
     * {@code evil-example.com} 不会被命中——这正是 {@code endsWith} 实现的经典漏洞。</p>
     */
    private void checkHttpUrl(String url) {
        String host = extractHost(url);
        boolean allowed = allowedDomains.stream().anyMatch(p -> p.matcher(host).matches());
        if (!allowed) {
            throw new SandboxViolationException("HTTP 域名不在白名单内: " + host);
        }
    }

    /**
     * 从 URL 里取出 host（去 scheme、去路径、去端口）。
     *
     * <p>刻意用最笨的字符串处理而不是 {@code URI}：模型传来的"URL"经常不合法
     * （少了 scheme、带中文、带空格），解析器一抛异常就只能放行或拒绝二选一；
     * 这里的容错口径是<b>尽量析出 host，析不出就当空串</b>，让白名单自然拒绝。</p>
     */
    private String extractHost(String url) {
        String s = url.replaceFirst("^[a-zA-Z]+://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        return s;
    }

    /**
     * 目录 Agent 的文件访问：解析真实路径后必须落在 {@code agentDir} 之内。
     *
     * <p>相对路径按 {@code agentDir} 解析——正文里写的 {@code skills/report-format.md}
     * 是相对 Agent 目录的，按 JVM 当前目录解析必然读不到。</p>
     */
    private void checkAgentFilePath(String path, Path agentDir) {
        Path base = realPathOf(agentDir);
        Path target = realPathOf(resolveAgainst(agentDir, path));
        if (!target.startsWith(base)) {
            throw new SandboxViolationException("文件路径不在该 Agent 目录内: " + path);
        }
    }

    /**
     * 目录 Agent 的命令执行：只允许 {@code <解释器> <本目录 scripts/xxx> [args]}。
     *
     * <p>三道判断缺一不可：解释器白名单（限定"能跑什么"）、脚本真实路径（限定"跑哪个"）、
     * 元字符与内联参数拒绝（限定"不会被拼出第二条命令"）。</p>
     */
    private void checkAgentScript(String command, Path agentDir) {
        int bad = indexOfForbiddenChar(command);
        if (bad >= 0) {
            throw new SandboxViolationException("命令中不允许出现 shell 元字符 '"
                    + command.charAt(bad) + "'（只允许执行本目录 scripts/ 下的脚本）");
        }

        List<String> tokens = tokenize(command);
        if (tokens.size() < 2) {
            throw new SandboxViolationException(
                    "命令必须是 <解释器> <本目录 scripts/ 下的脚本> 形式: " + command);
        }

        String interpreter = normalizeInterpreter(tokens.get(0));
        if (!allowedInterpreters.contains(interpreter)) {
            throw new SandboxViolationException("解释器不在白名单内: " + tokens.get(0)
                    + "（允许: " + allowedInterpreters + "）");
        }

        String script = tokens.get(1);
        if (script.startsWith("-")) {
            throw new SandboxViolationException("不允许内联代码参数（-c / -m / - 等）: " + script);
        }

        Path scriptsDir = realPathOf(agentDir.resolve("scripts"));
        Path resolved = realPathOf(resolveAgainst(agentDir, script));
        if (!resolved.startsWith(scriptsDir) || !Files.isRegularFile(resolved)) {
            throw new SandboxViolationException("只允许执行本 Agent 目录下的脚本: " + script
                    + "（应位于 " + scriptsDir + " 内）");
        }
    }

    /** 相对路径按基准目录解析，绝对路径原样归一化。 */
    private static Path resolveAgainst(Path base, String path) {
        Path candidate = Paths.get(path);
        return candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
    }

    /** 返回第一个被禁止的字符下标；没有则返回 -1。 */
    private static int indexOfForbiddenChar(String command) {
        for (int i = 0; i < command.length(); i++) {
            if (FORBIDDEN_COMMAND_CHARS.indexOf(command.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 按空白切分命令，支持双引号与单引号包裹（脚本路径常带空格）。
     *
     * <p>不追求完整的 shell 语法——只要求"能认出解释器与脚本路径"，认不出的形态一律拒绝。</p>
     */
    static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean open = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                open = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (current.length() > 0 || open) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    open = false;
                }
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0 || open) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** 解释器名称归一化：取文件名、转小写、去掉 .exe（Windows 上常写全路径）。 */
    private static String normalizeInterpreter(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                case '.' -> sb.append("\\.");
                default -> sb.append(c);
            }
        }
        return sb.append("$").toString();
    }
}
