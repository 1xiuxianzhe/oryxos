package io.oryxos.tool.builtin;

import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件类内置工具：放行路径能跑通，越界路径被拦且**副作用为零**。
 *
 * <p>见 {@code specs/005-tool-system/contracts/tool-invocation.md} 第 6 节验证项 1、2、7。</p>
 */
class FileToolTest {

    private static Sandbox allowing(Path... dirs) {
        return new WhitelistSandbox(
                java.util.Arrays.stream(dirs).map(Path::toString).toList(),
                List.of(),
                List.of());
    }

    private static ToolResult run(String toolName, Sandbox sandbox, Map<String, Object> input) {
        return new FileTool(toolName, sandbox).execute(input);
    }

    // ---------- 放行 ----------

    @Test
    void read_file读取白名单内文件(@TempDir Path allowed) throws IOException {
        Path file = allowed.resolve("note.txt");
        Files.writeString(file, "文件内容");

        ToolResult result = run("read_file", allowing(allowed), Map.of("path", file.toString()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).isEqualTo("文件内容");
    }

    @Test
    void write_file写入白名单内文件(@TempDir Path allowed) throws IOException {
        Path file = allowed.resolve("out.txt");

        ToolResult result = run("write_file", allowing(allowed),
                Map.of("path", file.toString(), "content", "写进去的内容"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("写进去的内容");
    }

    @Test
    void list_dir列出白名单内目录(@TempDir Path allowed) throws IOException {
        Files.writeString(allowed.resolve("a.txt"), "a");
        Files.writeString(allowed.resolve("b.txt"), "b");

        ToolResult result = run("list_dir", allowing(allowed), Map.of("path", allowed.toString()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getContent()).contains("a.txt", "b.txt");
    }

    // ---------- 越界：被拦 + 副作用为零 ----------

    @Test
    void read_file越界被拒_且校验早于读取(@TempDir Path allowed, @TempDir Path outside) {
        // 刻意用一个不存在的路径：若先读取会报"文件不存在"，报的是白名单说明校验确实在前
        Path ghost = outside.resolve("does-not-exist.txt");

        ToolResult result = run("read_file", allowing(allowed), Map.of("path", ghost.toString()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("白名单");
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void write_file越界被拒_文件未被创建(@TempDir Path allowed, @TempDir Path outside) {
        Path target = outside.resolve("should-not-exist.txt");

        ToolResult result = run("write_file", allowing(allowed),
                Map.of("path", target.toString(), "content", "不该被写进去"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("白名单");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void list_dir越界被拒(@TempDir Path allowed, @TempDir Path outside) {
        ToolResult result = run("list_dir", allowing(allowed), Map.of("path", outside.toString()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void 白名单为空时一律拒绝(@TempDir Path any) throws IOException {
        Path file = any.resolve("f.txt");
        Files.writeString(file, "存在");

        ToolResult result = run("read_file", new WhitelistSandbox(List.of(), List.of(), List.of()),
                Map.of("path", file.toString()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("白名单");
    }

    // ---------- 入参 ----------

    @Test
    void 缺少path参数时失败且不可重试(@TempDir Path allowed) {
        ToolResult result = run("read_file", allowing(allowed), Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("path");
        assertThat(result.isRetryable()).isFalse();
    }
}
