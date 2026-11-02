package io.oryxos.memory.config;

import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.memory.LongTermMemory;
import io.oryxos.memory.MemoryFiles;
import io.oryxos.memory.MemoryLocator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆装配的验收测试：路径落在规范位置、两个工具真的进了共享注册表。
 */
class MemoryAutoConfigurationTest {

    @TempDir
    Path workspace;

    @Test
    void 装配后两个记忆工具进入注册表且路径落在规范位置() {
        MemoryAutoConfiguration config = new MemoryAutoConfiguration();
        LongTermMemory memory = config.longTermMemory(workspace.toString());
        MemoryLocator locator = config.memoryLocator(workspace.toString());
        MemoryService service = config.memoryService(locator);
        ToolRegistry registry = new ToolRegistry();

        config.memoryToolRegistration(registry, service).afterPropertiesSet();

        assertThat(memory.file()).isEqualTo(MemoryFiles.resolve(workspace));
        assertThat(memory.file().toString()).endsWith("memory" + java.io.File.separator + "MEMORY.md");
        assertThat(locator.currentFile()).isEqualTo(MemoryFiles.resolve(workspace).toAbsolutePath().normalize());
        assertThat(registry.contains("save_memory")).isTrue();
        assertThat(registry.contains("recall_memory")).isTrue();
        assertThat(registry.listAll()).extracting(OryxTool::getName)
                .contains("save_memory", "recall_memory");
    }

    @Test
    void 装配不创建记忆文件_文件由初始化命令负责() {
        MemoryAutoConfiguration config = new MemoryAutoConfiguration();

        LongTermMemory memory = config.longTermMemory(workspace.toString());

        assertThat(memory.file()).doesNotExist();
    }
}
