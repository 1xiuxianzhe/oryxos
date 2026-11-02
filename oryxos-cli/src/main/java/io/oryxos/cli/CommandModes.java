package io.oryxos.cli;

import java.util.Set;

/**
 * 命令行参数到运行模式的判定：只负责分流，不负责执行。
 *
 * <p>启动模块据此决定是否拉起 Spring 上下文，保证轻命令秒回。</p>
 */
public final class CommandModes {

    /**
     * 需要完整运行时的命令。
     *
     * <p>{@code tool} 也在这里：工具清单必须来自真实注册表，而注册表只在运行时里存在
     * （内置工具靠装配登记、外部工具靠启动期连接）。若让它留在轻命令路径上，
     * 就只能给出目录列举或硬编码清单——那正是本特性要消除的"影子清单"。</p>
     */
    private static final Set<String> HEAVY_COMMANDS = Set.of("chat", "serve", "gateway", "tool");
    private static final Set<String> DAEMON_COMMANDS = Set.of("serve", "gateway");
    private static final Set<String> COMMANDS = Set.of(
            "init", "status", "chat", "serve", "gateway",
            "profile", "provider", "tool", "session");
    private static final Set<String> HELP_FLAGS = Set.of("-h", "--help", "-V", "--version");

    private CommandModes() {
    }

    /** 判断本次进程启动是否是 OryxOS 命令调用，而非 Spring Boot 的普通启动。 */
    public static boolean isCliInvocation(String[] args) {
        return resolve(args) != null;
    }

    /** 命令的运行模式；应在 {@link #isCliInvocation(String[])} 为真时调用。 */
    public static CommandMode mode(String[] args) {
        return HEAVY_COMMANDS.contains(resolve(args)) ? CommandMode.HEAVY : CommandMode.LIGHT;
    }

    /** 是否为常驻命令（serve/gateway）：命令返回后不能立即退出进程。 */
    public static boolean isDaemon(String[] args) {
        return DAEMON_COMMANDS.contains(resolve(args));
    }

    /** 是否需要 Servlet 容器（只有 serve 需要）。 */
    public static boolean requiresWebServer(String[] args) {
        return "serve".equals(resolve(args));
    }

    private static String resolve(String[] args) {
        if (args == null) {
            return null;
        }
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            if (HELP_FLAGS.contains(arg)) {
                return "help";
            }
            if (arg.startsWith("-")) {
                continue;
            }
            return COMMANDS.contains(arg) ? arg : null;
        }
        return null;
    }
}
