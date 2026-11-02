package io.oryxos.core.profile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具授权声明的两种写法都要能解析：对象形式（既有）与简写形式（兼容增强）。
 *
 * <p>见 {@code specs/005-tool-system/contracts/tool-authorization.md} 第 2 节。</p>
 */
class ProfileToolDeclarationTest {

    @TempDir
    Path profilesDir;

    private void write(String fileName, String content) throws IOException {
        Files.writeString(profilesDir.resolve(fileName), content);
    }

    @Test
    void 对象写法解析出工具名() throws IOException {
        write("object-form.yaml", """
                provider:
                  name: deepseek
                  model: deepseek-chat
                tools:
                  - name: read_file
                  - name: shell
                """);

        ProfileLoader loader = new ProfileLoader(profilesDir, List.of("deepseek"));
        loader.loadAll();

        assertThat(loader.get("object-form").getTools())
                .extracting(Profile.ToolConfig::getName)
                .containsExactly("read_file", "shell");
    }

    @Test
    void 简写写法解析出工具名() throws IOException {
        write("short-form.yaml", """
                provider:
                  name: deepseek
                  model: deepseek-chat
                tools: [read_file, shell]
                """);

        ProfileLoader loader = new ProfileLoader(profilesDir, List.of("deepseek"));
        loader.loadAll();

        assertThat(loader.get("short-form").getTools())
                .extracting(Profile.ToolConfig::getName)
                .containsExactly("read_file", "shell");
    }

    @Test
    void 空声明解析为空列表() throws IOException {
        write("empty-form.yaml", """
                provider:
                  name: deepseek
                  model: deepseek-chat
                tools: []
                """);

        ProfileLoader loader = new ProfileLoader(profilesDir, List.of("deepseek"));
        loader.loadAll();

        assertThat(loader.get("empty-form").getTools()).isEmpty();
    }
}
