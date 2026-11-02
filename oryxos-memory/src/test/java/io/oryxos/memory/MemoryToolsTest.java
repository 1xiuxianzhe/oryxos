package io.oryxos.memory;

import io.oryxos.core.audit.ToolInvocationAuditor;
import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.react.ToolExecutor;
import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 两个记忆工具的验收测试：写入落对区块、缺省与非法取值、未命中不报错、经执行器落审计。
 */
class MemoryToolsTest {

    @TempDir
    Path workspace;

    private MemoryTools.SaveMemoryTool saveTool;
    private MemoryTools.RecallMemoryTool recallTool;

    @BeforeEach
    void setUp() {
        MemoryService memoryService =
                new DefaultMemoryService(new LongTermMemory(MemoryFiles.resolve(workspace)));
        saveTool = new MemoryTools.SaveMemoryTool(memoryService);
        recallTool = new MemoryTools.RecallMemoryTool(memoryService);
    }

    private String raw() throws IOException {
        return Files.readString(MemoryFiles.resolve(workspace), StandardCharsets.UTF_8);
    }

    @Test
    void save_memory缺省落归档区() throws IOException {
        ToolResult result = saveTool.execute(Map.of("content", "项目部署在 K8s"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("已记住").contains("归档记忆");
        assertThat(raw().substring(raw().indexOf(MemoryFiles.ARCHIVE_HEADER))).contains("项目部署在 K8s");
    }

    @Test
    void save_memory指定core落核心区且检索不返回它() throws IOException {
        ToolResult result = saveTool.execute(Map.of("content", "用户偏好中文", "scope", "CORE"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("核心记忆");
        assertThat(raw().substring(raw().indexOf(MemoryFiles.CORE_HEADER)))
                .contains("用户偏好中文");
        // 核心记忆永远在场，不需要（也不应该）靠检索找回来
        assertThat(recallTool.execute(Map.of("keyword", "用户偏好中文")).getContent())
                .isEqualTo("没有找到相关记忆");
    }

    @Test
    void save_memory缺少内容失败且不可重试() {
        ToolResult result = saveTool.execute(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("content");
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void save_memory的scope非法时失败且不写入() {
        ToolResult result = saveTool.execute(Map.of("content", "某条内容", "scope", "long-term"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("core").contains("archival");
        assertThat(result.isRetryable()).isFalse();
        assertThat(MemoryFiles.resolve(workspace)).doesNotExist();
    }

    @Test
    void recall_memory命中返回条目() {
        saveTool.execute(Map.of("content", "项目部署在 K8s"));

        ToolResult result = recallTool.execute(Map.of("keyword", "k8s"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("项目部署在 K8s");
    }

    @Test
    void recall_memory未命中返回提示而非异常() {
        saveTool.execute(Map.of("content", "项目部署在 K8s"));

        ToolResult result = recallTool.execute(Map.of("keyword", "天气"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("没有找到相关记忆");
    }

    @Test
    void recall_memory缺少关键词失败() {
        ToolResult result = recallTool.execute(Map.of("keyword", "  "));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("keyword");
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void 经工具执行器调用后写入审计() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(saveTool);
        ToolInvocationAuditor auditor = mock(ToolInvocationAuditor.class);
        ToolExecutor executor = new ToolExecutor(registry, null, auditor);

        ToolResult result = executor.execute("s-1",
                new LlmClient.ToolCall("save_memory", "{\"content\":\"审计这条\"}"));

        assertThat(result.isSuccess()).isTrue();
        verify(auditor).record(eq("s-1"), isNull(), eq("save_memory"), anyString(),
                eq(true), isNull(), anyString(), anyLong());
    }
}
