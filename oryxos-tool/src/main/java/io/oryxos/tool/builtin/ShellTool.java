package io.oryxos.tool.builtin;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.sandbox.SandboxViolationException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ShellTool —— 内置 Shell Tool，执行白名单内命令，默认超时 30 秒。
 *
 * <p><b>平台假设</b>：命令经 Windows 命令解释器执行，<b>不是</b> {@code bash}。
 * 与源文档的描述不一致，属既有独立缺口，本特性未改动，仅在此标注以免被误读为跨平台可用。</p>
 *
 * <p><b>目录 Agent 与 legacy Profile 的区别</b>：从 {@link AgentContext} 取到当前 Profile 的
 * {@code agentDir} 后，沙箱会把命令限制为"解释器 + 本目录 {@code scripts/} 下的脚本"。
 * 没有 {@code agentDir}（legacy）时行为完全不变——这是为了不把存量配置一起推倒。</p>
 */
public class ShellTool implements OryxTool {

    /** 默认命令超时。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 回填给模型的输出上限。
     *
     * <p>脚本打个 10MB 日志就原样塞进上下文，既会挤掉真正的信息、也会直接撞上下文上限，
     * 所以这里必须截断并且**明确告诉模型被截断了**（否则它会以为数据完整）。</p>
     */
    private static final int MAX_OUTPUT_CHARS = 32 * 1024;

    /** 失败时附带的输出尾部上限：够看清异常，又不至于把整段堆栈灌进上下文。 */
    private static final int ERROR_TAIL_CHARS = 2 * 1024;

    /** 读取时最多累积到上限的 2 倍（留出给"已截断"提示与错误尾部的余量）。 */
    private static final int ACCUMULATE_LIMIT = MAX_OUTPUT_CHARS * 2;

    private final Sandbox sandbox;
    private final Duration timeout;

    public ShellTool(Sandbox sandbox) {
        this(sandbox, DEFAULT_TIMEOUT);
    }

    /** 测试用：注入更短的超时，以便在不真正等待 30 秒的前提下验证"超时被终止"。 */
    ShellTool(Sandbox sandbox, Duration timeout) {
        this.sandbox = sandbox;
        this.timeout = timeout;
    }

    @Override
    public String getName() {
        return "shell";
    }

    @Override
    public String getDescription() {
        return "执行白名单内的 Shell 命令并返回输出。参数：command（命令字符串）。"
                + "目录 Agent 只允许执行 <解释器> <本目录 scripts/ 下的脚本>；"
                + "命令退出码非 0 视为失败。";
    }

    @Override
    public String getInputSchema() {
        return """
                {"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}
                """;
    }

    /**
     * 执行一条命令并返回结果。
     *
     * <p>关键顺序：<b>先过沙箱，再起进程</b>。沙箱拒绝时进程数必须是 0——
     * 如果先启动再校验，被拒绝的命令其实已经执行过一部分，校验就成了摆设。</p>
     *
     * <p>结果语义（模型据此决定下一步）：</p>
     * <ul>
     *   <li>退出码 0 → {@code ok(输出)}，输出超过 32KB 截断并标注；</li>
     *   <li>退出码非 0 → {@code fail(不可重试)}，附退出码与输出尾部；</li>
     *   <li>越界/超时 → {@code fail(不可重试)}；其它异常 → {@code fail(可重试)}。</li>
     * </ul>
     *
     * <p>{@code retryable} 目前只是审计可见的标记，ReAct 循环不会据此禁止再次调用
     * （见 spec C-9 的明确取舍）。</p>
     */
    @Override
    public ToolResult execute(Map<String, Object> input) {
        String command = (String) input.get("command");
        if (command == null || command.isBlank()) {
            return ToolResult.fail("缺少 command 参数", false);
        }
        Path agentDir = currentAgentDir();
        try {
            // 校验严格早于进程启动：拒绝时不会有任何进程被拉起
            sandbox.enforce(new SandboxAction(ActionType.SHELL_EXEC, command, agentDir));

            ProcessBuilder pb = new ProcessBuilder("cmd.exe", "/c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 先并发读、再等退出：输出超过管道缓冲区时，"先 waitFor 再读"会互相卡死
            StringBuilder output = new StringBuilder();
            Thread reader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), output));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.fail("命令执行超时（" + timeout.toSeconds() + "s）", false);
            }
            reader.join(TimeUnit.SECONDS.toMillis(5));

            int exitCode = process.exitValue();
            String text = output.toString().strip();

            if (exitCode != 0) {
                // 非 0 退出 = 失败：以前一律当成功回填，模型会把错误输出当成数据继续往下走
                String tail = tail(text, ERROR_TAIL_CHARS);
                String reason = "命令退出码 " + exitCode
                        + (tail.isBlank() ? "" : "；输出尾部:\n" + tail);
                return ToolResult.fail(reason, false);
            }
            return ToolResult.ok(truncate(text));
        } catch (SandboxViolationException e) {
            // 越界属于配置问题：重试一万次也不会变成允许，必须标记为不可重试
            return ToolResult.fail(e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("命令执行被中断", false);
        } catch (Exception e) {
            return ToolResult.fail(e.getMessage(), true);
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

    /** 把进程输出读干；超过累积上限后仍继续读（避免管道写满），但不再累积。 */
    private static void drain(InputStream stream, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sink.length() < ACCUMULATE_LIMIT) {
                    sink.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // 进程被强杀时流会异常中断，保留已读到的部分即可
        }
    }

    /**
     * 按上限截断输出，并显式告诉模型"被截断了"。
     *
     * <p>只截断不提示最危险：模型会把半截 JSON 当成完整数据，然后基于缺失的字段下结论。</p>
     */
    private static String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_CHARS)
                + "\n…（输出已截断：原始 " + text.length() + " 字符，仅保留前 " + MAX_OUTPUT_CHARS + " 字符）";
    }

    /**
     * 取文本末尾若干字符。
     *
     * <p>失败时附"尾部"而不是"开头"：脚本报错信息（堆栈、异常行）总在最后，
     * 只留开头等于只留一堆无意义的日志前缀。</p>
     */
    private static String tail(String text, int maxChars) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
    }
}
