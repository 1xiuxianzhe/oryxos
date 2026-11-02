package io.oryxos.cli;

import io.oryxos.channel.cli.CliChannel;
import io.oryxos.core.session.SessionManager;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;

@Command(
        name = "oryxos",
        description = "OryxOS - 面向企业场景的 Agent OS 运行时内核",
        mixinStandardHelpOptions = true,
        subcommands = {
                OryxOsCli.InitCommand.class,
                OryxOsCli.StatusCommand.class,
                OryxOsCli.ChatCommand.class,
                OryxOsCli.ServeCommand.class,
                OryxOsCli.GatewayCommand.class,
                ProfileCommand.class,
                ProviderCommand.class,
                ToolCommand.class,
                SessionCommand.class
        }
)
public class OryxOsCli implements Runnable {

    private final RuntimeLauncher runtimeLauncher;

    public OryxOsCli() {
        this(null);
    }

    public OryxOsCli(RuntimeLauncher runtimeLauncher) {
        this.runtimeLauncher = runtimeLauncher;
    }

    RuntimeLauncher runtimeLauncher() {
        return runtimeLauncher;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    @Command(name = "init", description = "初始化 .oryxos/ 工作区")
    static class InitCommand implements Runnable {
        @Override
        public void run() {
            try {
                WorkspaceLayout.current().initialize();
                System.out.println("工作区已初始化: " + WorkspaceLayout.current().root());
            } catch (IOException e) {
                throw new IllegalStateException("初始化工作区失败: " + e.getMessage(), e);
            }
        }
    }

    @Command(name = "status", description = "查看配置和运行状态")
    static class StatusCommand implements Runnable {
        @Override
        public void run() {
            WorkspaceLayout workspace = WorkspaceLayout.current();
            workspace.requireInitialized();
            long profiles;
            try {
                profiles = FilesSupport.countFiles(workspace.profiles(), ".yaml", ".yml");
            } catch (IOException e) {
                throw new IllegalStateException("读取 Profile 失败: " + e.getMessage(), e);
            }
            System.out.printf("workspace: %s%nprofiles: %d%nstatus: ready%n",
                    workspace.root(), profiles);
        }
    }

    @Command(name = "chat", description = "交互式对话")
    static class ChatCommand implements Runnable {
        @ParentCommand
        OryxOsCli parent;

        @Option(names = {"--profile", "-p"}, defaultValue = "default")
        String profile;

        @Override
        public void run() {
            if (parent.runtimeLauncher() == null) {
                throw new IllegalStateException("chat 需要通过 Spring Boot 运行时启动");
            }
            parent.runtimeLauncher().chatChannel().start(profile);
        }
    }

    @Command(name = "serve", description = "启动 HTTP API 服务")
    static class ServeCommand implements Runnable {
        @ParentCommand
        OryxOsCli parent;

        @Option(names = "--port", defaultValue = "8080")
        int port;

        @Override
        public void run() {
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("端口必须在 1-65535 范围内");
            }
            if (parent.runtimeLauncher() == null) {
                throw new IllegalStateException("serve 需要通过 Spring Boot 运行时启动");
            }
            parent.runtimeLauncher().serve(port);
        }
    }

    @Command(name = "gateway", description = "启动多渠道守护进程")
    static class GatewayCommand implements Runnable {
        @ParentCommand
        OryxOsCli parent;

        @Override
        public void run() {
            if (parent.runtimeLauncher() == null) {
                throw new IllegalStateException("gateway 需要通过 Spring Boot 运行时启动");
            }
            parent.runtimeLauncher().gateway();
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new OryxOsCli()).execute(args);
        System.exit(exitCode);
    }

    static final class FilesSupport {
        private FilesSupport() {
        }

        static long countFiles(java.nio.file.Path directory, String... suffixes) throws IOException {
            try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(directory)) {
                return files.filter(java.nio.file.Files::isRegularFile)
                        .filter(file -> {
                            String name = file.getFileName().toString();
                            for (String suffix : suffixes) {
                                if (name.endsWith(suffix)) {
                                    return true;
                                }
                            }
                            return false;
                        })
                        .count();
            }
        }
    }
}
