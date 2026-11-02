package io.oryxos.cli;

import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Command(
        name = "provider",
        description = "Provider 管理",
        subcommands = ProviderCommand.ListCommand.class
)
public class ProviderCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("provider: 使用 list 子命令");
    }

    @Command(name = "list", description = "列出已配置 Provider")
    static class ListCommand implements Runnable {
        @Override
        public void run() {
            WorkspaceLayout workspace = WorkspaceLayout.current();
            workspace.requireInitialized();
            Path directory = workspace.providers();
            if (!Files.isDirectory(directory)) {
                printGlobalHint();
                return;
            }
            List<String> names;
            try (Stream<Path> files = Files.list(directory)) {
                names = files.filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList();
            } catch (IOException e) {
                throw new IllegalStateException("读取 Provider 失败: " + e.getMessage(), e);
            }
            if (names.isEmpty()) {
                printGlobalHint();
                return;
            }
            names.forEach(System.out::println);
        }

        /** 全局层声明的 Provider 不落工作区文件；这里如实说明，不伪造列表。 */
        private static void printGlobalHint() {
            System.out.println("工作区未定义 Provider 文件。");
            System.out.println("Provider 在 application.yaml 的 oryxos.providers 中声明（name / base-url / api-key）。");
        }
    }
}
