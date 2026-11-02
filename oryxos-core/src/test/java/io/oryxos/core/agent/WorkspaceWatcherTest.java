package io.oryxos.core.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目录监听的判定逻辑。
 *
 * <p>刻意<b>不</b>依赖操作系统的文件事件时序（那会让测试在 CI 上随机失败），
 * 而是直接驱动"判定"这一段：真实事件与测试走的是同一个 {@code process}。</p>
 */
class WorkspaceWatcherTest {

    @TempDir
    Path workspace;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private WorkspaceWatcher watcher;

    @BeforeEach
    void setUp() {
        watcher = new WorkspaceWatcher(workspace.resolve("agents"), new WorkspaceWatcher.AgentChangeListener() {
            @Override
            public void onChanged(Path agentDir) {
                events.add("changed:" + agentDir.getFileName());
            }

            @Override
            public void onRemoved(String name) {
                events.add("removed:" + name);
            }
        }, 30);
    }

    private Path writeAgent(String name, String content) throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("agents").resolve(name));
        Files.writeString(dir.resolve("AGENT.md"), content, StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    void 有AGENT主文件的目录触发变更() throws Exception {
        Path dir = writeAgent("daily-pr", "---\ndescription: x\n---\n正文");

        watcher.processNow(dir);

        assertThat(events).containsExactly("changed:daily-pr");
    }

    @Test
    void 目录消失触发注销() {
        watcher.processNow(workspace.resolve("agents").resolve("gone"));

        assertThat(events).containsExactly("removed:gone");
    }

    @Test
    void 没有AGENT主文件时既不注册也不注销() throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("agents").resolve("half-copied"));

        watcher.processNow(dir);

        assertThat(events).isEmpty();
    }

    @Test
    void 去抖窗口内的多次事件只处理一次() throws Exception {
        Path dir = writeAgent("daily-pr", "---\ndescription: x\n---\n正文");

        watcher.handleChange(dir);
        watcher.handleChange(dir);
        watcher.handleChange(dir);
        Thread.sleep(400);

        assertThat(events).containsExactly("changed:daily-pr");
    }

    @Test
    void 监听器抛异常不会冒到监听线程() throws Exception {
        Path dir = writeAgent("boom", "---\ndescription: x\n---\n正文");
        WorkspaceWatcher throwing = new WorkspaceWatcher(workspace.resolve("agents"),
                new WorkspaceWatcher.AgentChangeListener() {
                    @Override
                    public void onChanged(Path agentDir) {
                        throw new IllegalStateException("监听器内部炸了");
                    }

                    @Override
                    public void onRemoved(String name) {
                        throw new IllegalStateException("监听器内部炸了");
                    }
                }, 10);

        throwing.processNow(dir);
        throwing.handleChange(dir);
        Thread.sleep(200);

        // 没有异常抛出即为通过：一条事件处理失败不该拖垮后续事件
        assertThat(throwing.isRunning()).isFalse();
    }
}
