package io.oryxos;

import io.oryxos.boot.ConsoleEncoding;
import io.oryxos.cli.CommandMode;
import io.oryxos.cli.CommandModes;
import io.oryxos.cli.OryxOsCli;
import io.oryxos.cli.RuntimeLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

/**
 * 单二进制启动入口：把命令行参数分流到轻命令（不启 Spring）或重命令（启 Spring）。
 *
 * <p>无 CLI 参数时退化为普通 Spring Boot 启动，保持既有行为不变。</p>
 */
public final class OryxosLauncher {

    private OryxosLauncher() {
    }

    public static void main(String[] args) {
        // 必须早于 Spring：Logback 的 ConsoleAppender 在初始化时会捕获当时的 System.out
        ConsoleEncoding.installConsoleStreams();

        if (!CommandModes.isCliInvocation(args)) {
            SpringApplication.run(OryxosApplication.class, args);
            return;
        }
        if (CommandModes.mode(args) == CommandMode.LIGHT) {
            System.exit(new CommandLine(new OryxOsCli()).execute(args));
        }
        runWithSpring(args);
    }

    private static void runWithSpring(String[] args) {
        SpringApplication application = new SpringApplication(OryxosApplication.class);
        application.setWebApplicationType(CommandModes.requiresWebServer(args)
                ? WebApplicationType.SERVLET : WebApplicationType.NONE);

        ConfigurableApplicationContext context = application.run(springOverrides(args));
        RuntimeLauncher launcher = context.getBean(RuntimeLauncher.class);
        int exitCode = new CommandLine(new OryxOsCli(launcher)).execute(args);

        if (exitCode != 0 || !CommandModes.isDaemon(args)) {
            context.close();
            System.exit(exitCode);
        }
        awaitShutdown(context);
    }

    /** 只把需要覆盖的 Spring 属性传给容器，避免 CLI 参数污染配置。 */
    private static String[] springOverrides(String[] args) {
        List<String> overrides = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if ("--port".equals(args[i])) {
                overrides.add("--server.port=" + args[i + 1]);
            }
        }
        return overrides.toArray(String[]::new);
    }

    /** 常驻模式：serve 由 Servlet 容器线程保活，gateway 无 Web 线程需显式阻塞。 */
    private static void awaitShutdown(ConfigurableApplicationContext context) {
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            context.close();
        }
    }
}
