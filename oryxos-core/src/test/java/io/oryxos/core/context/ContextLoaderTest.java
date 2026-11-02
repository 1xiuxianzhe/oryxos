package io.oryxos.core.context;

import io.oryxos.core.profile.Profile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ContextLoader 验收测试：无缓存（改文件立即生效）、显式引用缺失报错、默认三件套缺失只 WARN。
 */
class ContextLoaderTest {

    @TempDir
    Path workspace;

    private void write(String relativePath, String content) throws IOException {
        Path file = workspace.resolve(relativePath);
        Files.createDirectories(file.getParent() == null ? workspace : file.getParent());
        Files.writeString(file, content);
    }

    private Profile profileWithBootstrapFiles(List<String> files) {
        Profile profile = new Profile();
        profile.setName("ops-agent");
        Profile.BootstrapConfig bootstrap = new Profile.BootstrapConfig();
        bootstrap.setFiles(files);
        profile.setBootstrap(bootstrap);
        return profile;
    }

    @Test
    void 改文件后下一次立即读到新内容_无缓存() throws IOException {
        write("AGENTS.md", "旧的项目说明");
        Profile profile = profileWithBootstrapFiles(List.of("AGENTS.md"));
        ContextLoader loader = new ContextLoader(workspace);

        assertThat(loader.loadSystemContext(profile)).contains("旧的项目说明");

        write("AGENTS.md", "新的项目说明");

        assertThat(loader.loadSystemContext(profile)).contains("新的项目说明");
    }

    @Test
    void 显式引用的Bootstrap文件缺失_报错() {
        Profile profile = profileWithBootstrapFiles(List.of("NOT_EXIST.md"));
        ContextLoader loader = new ContextLoader(workspace);

        assertThatThrownBy(() -> loader.loadSystemContext(profile))
                .isInstanceOf(ContextLoadException.class)
                .hasMessageContaining("NOT_EXIST.md");
    }

    @Test
    void 未配置bootstrap_默认三件套缺失只告警不阻断() throws IOException {
        // 三件套一个都不存在
        Profile profile = profileWithBootstrapFiles(List.of());
        ContextLoader loader = new ContextLoader(workspace);

        // 不抛异常，返回空上下文（仅 identity 为空 + 无文件）
        assertThat(loader.loadSystemContext(profile)).isEmpty();

        // 存在其一则被读取，其余缺失仍只告警
        write("SOUL.md", "你是严谨的运维助手");
        assertThat(loader.loadSystemContext(profile)).contains("严谨的运维助手");
    }

    @Test
    void 显式引用的SKILL缺失_报错() {
        Profile profile = new Profile();
        profile.setName("ops-agent");
        Profile.SkillConfig skill = new Profile.SkillConfig();
        skill.setName("pr-digest");
        profile.setSkills(List.of(skill));
        ContextLoader loader = new ContextLoader(workspace);

        assertThatThrownBy(() -> loader.loadSystemContext(profile))
                .isInstanceOf(ContextLoadException.class)
                .hasMessageContaining("pr-digest");
    }

    @Test
    void 技能文件存在时被读取_内联prompt优先() throws IOException {
        write("skills/pr-digest/SKILL.md", "总结这个 PR 的要点");
        Profile profile = new Profile();
        profile.setName("ops-agent");

        Profile.SkillConfig fromFile = new Profile.SkillConfig();
        fromFile.setName("pr-digest");
        profile.setSkills(List.of(fromFile));
        ContextLoader loader = new ContextLoader(workspace);
        assertThat(loader.loadSystemContext(profile)).contains("总结这个 PR 的要点");

        Profile.SkillConfig inline = new Profile.SkillConfig();
        inline.setName("pr-digest");
        inline.setPrompt("内联的技能说明");
        profile.setSkills(List.of(inline));
        assertThat(loader.loadSystemContext(profile)).contains("内联的技能说明");
    }
}
