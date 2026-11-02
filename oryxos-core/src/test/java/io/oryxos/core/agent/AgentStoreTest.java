package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentLoader;
import io.oryxos.core.profile.ProfileRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 目录存储层：脚手架长什么样、写盘是否原子、归档会不会覆盖、回滚删不删得干净。
 *
 * <p>这些断言全部是"磁盘事实"，不涉及注册表与调度器——那属于生命周期服务的测试。</p>
 */
class AgentStoreTest {

    @TempDir
    Path workspace;

    private AgentStore store() {
        return new AgentStore(workspace, "deepseek", "deepseek-flash");
    }

    @Test
    void 脚手架产出四项且能被解析() throws Exception {
        AgentStore store = store();

        Path dir = store.createScaffold("daily-pr", "每天汇总 PR 并推送摘要");

        assertThat(dir).isEqualTo(store.agentDir("daily-pr"));
        assertThat(dir.resolve("AGENT.md")).isRegularFile();
        assertThat(dir.resolve("REFERENCE.md")).isRegularFile();
        assertThat(dir.resolve("scripts")).isDirectory();
        assertThat(dir.resolve("skills")).isDirectory();
        // memory/ 刻意不建：骨架由记忆门面自己写，抢先建空文件会让它读到没有区块的坏文件
        assertThat(dir.resolve("memory")).doesNotExist();

        AgentLoader.Derived derived = new AgentLoader(store.agentsDir(), Set.of("deepseek"), new ProfileRegistry())
                .derive(dir);
        assertThat(derived.profile().getName()).isEqualTo("daily-pr");
        assertThat(derived.profile().getProvider().getModel()).isEqualTo("deepseek-flash");
        assertThat(derived.profile().getTools()).extracting(tool -> tool.getName()).containsExactly("read_file");
        assertThat(derived.definition().body()).contains("每天汇总 PR 并推送摘要");
    }

    @Test
    void 描述里的冒号与引号不会打散frontmatter() throws Exception {
        AgentStore store = store();

        Path dir = store.createScaffold("tricky", "负责: \"复盘\" 与\n换行");

        String raw = Files.readString(dir.resolve("AGENT.md"), StandardCharsets.UTF_8);
        assertThat(raw).contains("description: \"负责: \\\"复盘\\\" 与 换行\"");

        AgentLoader.Derived derived = new AgentLoader(store.agentsDir(), Set.of("deepseek"), new ProfileRegistry())
                .derive(dir);
        assertThat(derived.profile().getDescription()).isEqualTo("负责: \"复盘\" 与 换行");
        assertThat(derived.profile().getProvider().getName()).isEqualTo("deepseek");
    }

    @Test
    void 未配置provider时模板不写provider段() throws Exception {
        AgentStore store = new AgentStore(workspace, null, null);

        Path dir = store.createScaffold("noprovider", "没有 provider");

        assertThat(Files.readString(dir.resolve("AGENT.md"), StandardCharsets.UTF_8))
                .doesNotContain("provider:");
        assertThatThrownBy(() -> new AgentLoader(store.agentsDir(), Set.of("deepseek"), new ProfileRegistry())
                .derive(dir))
                .hasMessageContaining("provider");
    }

    @Test
    void 原子写不留下临时文件() throws Exception {
        AgentStore store = store();
        Path dir = Files.createDirectories(store.agentDir("atomic"));
        Path target = dir.resolve("AGENT.md");

        store.writeAtomically(target, "第一版");

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("第一版");
        try (Stream<Path> stream = Files.list(dir)) {
            assertThat(stream.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.contains(".tmp-"));
        }
    }

    @Test
    void 归档带时间戳且同名不覆盖() throws Exception {
        AgentStore store = store();
        store.createScaffold("daily-pr", "第一次");

        Path first = store.archive("daily-pr");
        assertThat(first.getFileName().toString()).startsWith("daily-pr-");
        assertThat(Files.isDirectory(first)).isTrue();
        assertThat(first.resolve("AGENT.md")).isRegularFile();
        assertThat(store.agentDir("daily-pr")).doesNotExist();

        // 同名再删一次（例如删了又建再删）：必须追加序号，绝不覆盖
        store.createScaffold("daily-pr", "第二次");
        Path second = store.archive("daily-pr");

        assertThat(second).isNotEqualTo(first);
        assertThat(second.getFileName().toString()).startsWith(first.getFileName().toString() + "-");
        assertThat(Files.isDirectory(first)).isTrue();
    }

    @Test
    void 回滚删除会把整棵目录清干净() throws Exception {
        AgentStore store = store();
        Path dir = store.createScaffold("cleanup", "删掉");
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("scripts").resolve("run.sh"), "echo hi");

        store.deleteTree(dir);

        assertThat(dir).doesNotExist();
        // 幂等：再删一次不抛
        store.deleteTree(dir);
    }

    @Test
    void 归档不存在的目录会报错而不是静默成功() {
        AgentStore store = store();

        assertThatThrownBy(() -> store.archive("ghost"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void 名字校验与29节规则一致() {
        assertThat(AgentLoader.isValidName("daily-pr")).isTrue();
        assertThat(AgentLoader.isValidName("a1")).isTrue();
        assertThat(AgentLoader.isValidName("Daily")).isFalse();
        assertThat(AgentLoader.isValidName("daily_pr")).isFalse();
        assertThat(AgentLoader.isValidName("日报")).isFalse();
        assertThat(AgentLoader.isValidName("-lead")).isFalse();
        assertThat(AgentLoader.isValidName(null)).isFalse();
    }

    @Test
    void 目录布局与记忆路径约定一致() {
        AgentStore store = store();

        assertThat(store.agentsDir()).isEqualTo(workspace.toAbsolutePath().normalize().resolve("agents"));
        assertThat(store.archiveDir()).isEqualTo(workspace.toAbsolutePath().normalize().resolve("archive"));
        assertThat(List.of(AgentStore.SCRIPTS_DIR, AgentStore.SKILLS_DIR, AgentStore.REFERENCE_FILE))
                .containsExactly("scripts", "skills", "REFERENCE.md");
    }
}
