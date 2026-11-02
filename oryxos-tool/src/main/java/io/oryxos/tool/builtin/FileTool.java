package io.oryxos.tool.builtin;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * FileTool —— 内置文件 Tool：read_file / write_file / list_dir，执行前过一次沙箱。
 *
 * <p>只依赖 {@link Sandbox} 抽象：本类不认识"白名单"，只表达"要访问这个路径"。</p>
 *
 * <p><b>目录 Agent 的路径基准</b>：正文里天然会写 {@code skills/report-format.md} 这种相对路径，
 * 若按 JVM 当前目录解析，永远读不到自己 Agent 目录里的文件。因此当当前 Profile 带
 * {@code agentDir} 时，相对路径一律按该目录解析，并把 {@code agentDir} 一并交给沙箱判定归属。</p>
 */
public class FileTool implements OryxTool {

    private final String name;
    private final Sandbox sandbox;

    public FileTool(String name, Sandbox sandbox) {
        this.name = name;
        this.sandbox = sandbox;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return switch (name) {
            case "read_file" -> "读取文件内容。参数：path（文件路径）。";
            case "write_file" -> "写入文件内容。参数：path（文件路径）、content（内容）。";
            case "list_dir" -> "列出目录内容。参数：path（目录路径）。";
            default -> name;
        };
    }

    @Override
    public String getInputSchema() {
        return switch (name) {
            case "read_file", "list_dir" -> """
                    {"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}
                    """;
            case "write_file" -> """
                    {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}
                    """;
            default -> "{}";
        };
    }

    /**
     * 执行 read_file / write_file / list_dir 三个动作之一。
     *
     * <p>固定顺序是 <b>解析路径 → 过沙箱 → 再做 IO</b>：沙箱必须先于任何读写执行，
     * 否则"被拒绝"这条路径上已经发生过副作用，拒绝就失去意义了。</p>
     *
     * <p>失败一律返回 {@code ToolResult.fail} 而不是抛异常：工具失败要作为一条可读的工具结果
     * 回填给模型（让它换个思路），而不是打断整轮对话。</p>
     */
    @Override
    public ToolResult execute(Map<String, Object> input) {
        String path = (String) input.get("path");
        if (path == null || path.isBlank()) {
            return ToolResult.fail("缺少 path 参数", false);
        }
        try {
            Path agentDir = currentAgentDir();
            Path p = resolvePath(path, agentDir);

            // 校验严格早于任何读写：拒绝时下面的 IO 一行都不会执行
            sandbox.enforce(new SandboxAction(ActionType.FILE_ACCESS, p.toString(), agentDir));
            return switch (name) {
                case "read_file" -> ToolResult.ok(Files.readString(p, StandardCharsets.UTF_8));
                case "write_file" -> {
                    String content = (String) input.getOrDefault("content", "");
                    Files.writeString(p, content, StandardCharsets.UTF_8);
                    yield ToolResult.ok("写入成功");
                }
                case "list_dir" -> {
                    var sb = new StringBuilder();
                    try (var stream = Files.list(p)) {
                        stream.forEach(f -> sb.append(f.getFileName()).append("\n"));
                    }
                    yield ToolResult.ok(sb.toString());
                }
                default -> ToolResult.fail("未知操作: " + name, false);
            };
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage(), false);
        }
    }

    /** 当前处理上下文所属的 Agent 目录；不在处理上下文或为 legacy Profile 时返回 {@code null}。 */
    private static Path currentAgentDir() {
        try {
            Profile profile = AgentContext.current();
            return profile == null ? null : profile.getAgentDir();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 解析实际路径：目录 Agent 的相对路径按 {@code agentDir} 解析，绝对路径原样归一化；
     * legacy 场景保持"相对 JVM 当前目录"的既有行为。
     */
    private static Path resolvePath(String path, Path agentDir) {
        Path candidate = Paths.get(path);
        if (candidate.isAbsolute() || agentDir == null) {
            return candidate;
        }
        return agentDir.resolve(candidate).normalize();
    }
}
