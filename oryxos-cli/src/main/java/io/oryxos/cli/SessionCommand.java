package io.oryxos.cli;

import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.file.Files;

@Command(
        name = "session",
        description = "Session 管理",
        subcommands = SessionCommand.ListCommand.class
)
public class SessionCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("session: 使用 list 子命令");
    }

    @Command(name = "list", description = "列出会话")
    static class ListCommand implements Runnable {
        @Override
        public void run() {
            WorkspaceLayout workspace = WorkspaceLayout.current();
            workspace.requireInitialized();
            if (Files.notExists(workspace.sessions())) {
                System.out.println("暂无 Session");
                return;
            }
            try {
                System.out.println("Session 存储: " + workspace.sessions());
            } catch (Exception e) {
                throw new IllegalStateException("读取 Session 失败: " + e.getMessage(), e);
            }
        }
    }
}
