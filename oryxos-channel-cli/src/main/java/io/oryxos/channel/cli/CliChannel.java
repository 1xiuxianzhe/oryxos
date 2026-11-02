package io.oryxos.channel.cli;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * CLI Channel：输入循环只负责渠道协议，Agent 处理统一委托给 AgentService。
 *
 * <p>中文编码说明：交互式终端下优先使用 {@link Console}，它在 Windows 上底层是
 * {@code ReadConsoleW}/{@code WriteConsoleW} 宽字符 API，不受控制台代码页影响；
 * 而 {@code System.out.println(String)} 走 {@code PrintStream} 自己的 charset
 * （Windows 中文系统默认取 ANSI 代码页 GBK），在 UTF-8 终端下会乱码。
 * 输入被重定向（管道/文件）时 {@code System.console()} 为 null，回退到 UTF-8 流式读写。</p>
 */
public class CliChannel {

    private final SessionManager sessionManager;
    private final AgentService agentService;
    private final InputStream input;
    private final PrintStream output;

    /** 仅当调用方未注入自定义流时才允许使用交互式 Console。 */
    private final boolean consoleAware;

    /** 兼容尚未接入运行时的旧调用方，使用轻量 echo 之外的明确错误提示。 */
    public CliChannel(SessionManager sessionManager) {
        this(sessionManager, null, System.in, System.out);
    }

    public CliChannel(SessionManager sessionManager, AgentService agentService) {
        this(sessionManager, agentService, System.in, System.out);
    }

    public CliChannel(SessionManager sessionManager, AgentService agentService,
                      InputStream input, PrintStream output) {
        this.sessionManager = sessionManager;
        this.agentService = agentService;
        this.input = input;
        this.output = output;
        this.consoleAware = input == System.in && output == System.out;
    }

    public void start(String profileName) {
        Session session = sessionManager.getOrCreate("cli", "local", profileName);
        Console console = consoleAware ? System.console() : null;
        if (console != null) {
            runInteractive(console, session);
        } else {
            runWithStreams(session);
        }
    }

    /** 交互式终端：走 OS 宽字符 API，中文收发与控制台代码页无关。 */
    private void runInteractive(Console console, Session session) {
        PrintWriter writer = console.writer();
        writer.println("OryxOS 交互模式（输入 /quit 退出）");
        writer.flush();
        String line;
        while ((line = console.readLine("> ")) != null) {
            if (!handleLine(session, line, writer::println)) {
                break;
            }
            writer.flush();
        }
    }

    /** 重定向场景：stdin/stdout 已被占用，按 UTF-8 字节流读写。 */
    private void runWithStreams(Session session) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        output.println("OryxOS 交互模式（输入 /quit 退出）");
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!handleLine(session, line, output::println)) {
                    break;
                }
            }
        } catch (IOException e) {
            output.println("CLI 读取异常: " + safeMessage(e));
        }
    }

    /**
     * 处理一行输入。
     *
     * @return false 表示应当退出交互循环（用户输入了 /quit）
     */
    private boolean handleLine(Session session, String rawLine, Consumer<String> printer) {
        String message = rawLine == null ? "" : rawLine.trim();
        if ("/quit".equalsIgnoreCase(message)) {
            return false;
        }
        if (message.isBlank()) {
            return true;
        }
        if (agentService == null) {
            printer.accept("CLI AgentService 尚未配置，请通过运行时启动 chat");
            return true;
        }
        try {
            printer.accept(agentService.process(session, message));
        } catch (RuntimeException e) {
            printer.accept("处理失败: " + safeMessage(e));
        }
        return true;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
