package io.oryxos.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

import java.util.List;

@Command(
        name = "tool",
        description = "Tool 管理",
        subcommands = ToolCommand.ListCommand.class
)
public class ToolCommand implements Runnable {

    @ParentCommand
    OryxOsCli root;

    @Override
    public void run() {
        System.out.println("tool: 使用 list 子命令");
    }

    @Command(name = "list", description = "列出已注册 Tool（来自真实注册表）")
    static class ListCommand implements Runnable {

        @ParentCommand
        ToolCommand parent;

        @Override
        public void run() {
            WorkspaceLayout workspace = WorkspaceLayout.current();
            workspace.requireInitialized();

            // 清单必须读注册表，而注册表只在运行时里存在；没有运行时宁可明确失败，
            // 也不要退回"列工作区目录"这种与真实状态无关的答案。
            if (parent.root.runtimeLauncher() == null) {
                throw new IllegalStateException("tool list 需要通过 Spring Boot 运行时启动");
            }

            List<String> names = parent.root.runtimeLauncher().registeredTools();
            if (names.isEmpty()) {
                System.out.println("当前没有可用工具：内置工具需先配置白名单（oryxos.sandbox），"
                        + "外部工具需在工作区 mcp_servers.yaml 中声明。");
                return;
            }
            names.forEach(System.out::println);
        }
    }
}
