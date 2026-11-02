package io.oryxos.memory;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.profile.Profile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每个目录 Agent 一份记忆：互不可见，legacy 与无上下文场景回退工作区记忆。
 */
class PerAgentMemoryTest {

    @TempDir
    Path workspace;

    private Profile profileOf(String name, String dirName) throws Exception {
        Path dir = workspace.resolve("agents").resolve(dirName);
        Files.createDirectories(dir);
        Profile profile = new Profile();
        profile.setName(name);
        profile.setAgentDir(dir.toAbsolutePath().normalize());
        return profile;
    }

    @AfterEach
    void tearDown() {
        AgentContext.clear();
    }

    @Test
    void 两个Agent的记忆互不可见() throws Exception {
        MemoryLocator locator = new MemoryLocator(workspace);
        DefaultMemoryService service = new DefaultMemoryService(locator);
        Profile agentA = profileOf("agent-a", "agent-a");
        Profile agentB = profileOf("agent-b", "agent-b");

        AgentContext.set(agentA);
        service.remember("A 的对账结论", MemoryScope.CORE);

        AgentContext.set(agentB);
        service.remember("B 的对账结论", MemoryScope.CORE);

        assertThat(service.snapshot()).contains("B 的对账结论").doesNotContain("A 的对账结论");

        AgentContext.set(agentA);
        assertThat(service.snapshot()).contains("A 的对账结论").doesNotContain("B 的对账结论");
        assertThat(locator.currentFile())
                .isEqualTo(MemoryFiles.resolve(agentA.getAgentDir()).toAbsolutePath().normalize());
    }

    @Test
    void 无处理上下文时回退工作区记忆() {
        MemoryLocator locator = new MemoryLocator(workspace);
        DefaultMemoryService service = new DefaultMemoryService(locator);

        service.remember("全局记忆", MemoryScope.CORE);

        assertThat(locator.currentFile())
                .isEqualTo(MemoryFiles.resolve(workspace).toAbsolutePath().normalize());
        assertThat(MemoryFiles.resolve(workspace)).exists();
        AgentContext.clear();
        assertThat(service.snapshot()).contains("全局记忆");
    }

    @Test
    void legacyProfile回退工作区记忆() throws Exception {
        MemoryLocator locator = new MemoryLocator(workspace);
        DefaultMemoryService service = new DefaultMemoryService(locator);
        Profile legacy = new Profile();
        legacy.setName("legacy");
        AgentContext.set(legacy);

        service.remember("legacy 记忆", MemoryScope.CORE);

        assertThat(locator.currentFile())
                .isEqualTo(MemoryFiles.resolve(workspace).toAbsolutePath().normalize());
        assertThat(service.snapshot()).contains("legacy 记忆");
    }

    @Test
    void 同一路径复用同一实例以保住写锁语义() throws Exception {
        MemoryLocator locator = new MemoryLocator(workspace);
        Profile agentA = profileOf("agent-a", "agent-a");
        AgentContext.set(agentA);

        Path file = locator.currentFile();

        assertThat(locator.forFile(file)).isSameAs(locator.current());
    }
}
