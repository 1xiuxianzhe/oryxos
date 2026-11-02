package io.oryxos.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 记忆文件路径/骨架/迁移的验收测试（对应初始化命令的行为契约）。
 */
class MemoryFilesTest {

    @TempDir
    Path workspace;

    private Path memoryFile() {
        return MemoryFiles.resolve(workspace);
    }

    private Path legacyFile() {
        return MemoryFiles.legacyFile(workspace);
    }

    @Test
    void 初始化创建骨架且重复执行不覆盖() throws IOException {
        MemoryFiles.initialize(workspace);
        Files.writeString(memoryFile(), Files.readString(memoryFile()) + "- [2026-10-16] 已有内容\n");

        MemoryFiles.initialize(workspace);
        MemoryFiles.initialize(workspace);

        assertThat(Files.readString(memoryFile()))
                .contains(MemoryFiles.TITLE)
                .contains(MemoryFiles.CORE_HEADER)
                .contains(MemoryFiles.ARCHIVE_HEADER)
                .contains("已有内容");
    }

    @Test
    void 有效内容判定忽略空行与标题行() {
        assertThat(MemoryFiles.effectiveLines("\n# 标题\n\n- 有内容\n")).containsExactly("- 有内容");
        assertThat(MemoryFiles.effectiveLines("   \n\n")).isEmpty();
        assertThat(MemoryFiles.effectiveLines(null)).isEmpty();
    }

    @Test
    void 带BOM的遗留文件_标题行不会被当成记忆条目() throws IOException {
        Files.writeString(legacyFile(), "\uFEFF" + MemoryFiles.TITLE + "\n用户偏好中文\n");

        assertThat(MemoryFiles.migrateLegacy(workspace))
                .isEqualTo(MemoryFiles.LegacyMigrationResult.MIGRATED);

        String migrated = Files.readString(memoryFile());
        assertThat(migrated).contains("用户偏好中文");
        assertThat(migrated).doesNotContain("- [2026-10-16] " + MemoryFiles.TITLE);
    }

    @Test
    void 没有遗留文件时不迁移也不产生副作用() throws IOException {
        assertThat(MemoryFiles.migrateLegacy(workspace))
                .isEqualTo(MemoryFiles.LegacyMigrationResult.NO_LEGACY);
        // 创建骨架是 initialize 的职责，迁移函数不越权建文件
        assertThat(memoryFile()).doesNotExist();
    }

    @Test
    void 遗留文件只有标题时视为无有效内容_原样保留() throws IOException {
        Files.writeString(legacyFile(), MemoryFiles.TITLE + "\n\n");

        assertThat(MemoryFiles.migrateLegacy(workspace))
                .isEqualTo(MemoryFiles.LegacyMigrationResult.NOTHING_TO_MIGRATE);

        assertThat(legacyFile()).exists();
        assertThat(memoryFile()).doesNotExist();
    }

    @Test
    void 遗留文件有内容时迁到归档区并备份旧文件() throws IOException {
        Files.writeString(legacyFile(), MemoryFiles.TITLE + "\n用户偏好中文\n");
        Files.writeString(workspace.resolve("USER.md"), "只读的用户设定");

        assertThat(MemoryFiles.migrateLegacy(workspace))
                .isEqualTo(MemoryFiles.LegacyMigrationResult.MIGRATED);

        String migrated = Files.readString(memoryFile());
        assertThat(migrated).contains("用户偏好中文");
        assertThat(migrated.substring(migrated.indexOf(MemoryFiles.ARCHIVE_HEADER)))
                .contains("用户偏好中文");
        assertThat(legacyFile()).doesNotExist();
        assertThat(workspace.resolve(MemoryFiles.FILE_NAME + MemoryFiles.BACKUP_SUFFIX)).exists();
        // 迁移绝不触碰只读的 USER.md
        assertThat(Files.readString(workspace.resolve("USER.md"))).isEqualTo("只读的用户设定");
    }

    @Test
    void 规范位置已有内容时跳过迁移且不覆盖() throws IOException {
        Files.writeString(legacyFile(), "旧的记忆\n");
        MemoryFiles.initialize(workspace);
        Files.writeString(memoryFile(), Files.readString(memoryFile()) + "- [2026-10-16] 新的记忆\n");

        assertThat(MemoryFiles.migrateLegacy(workspace))
                .isEqualTo(MemoryFiles.LegacyMigrationResult.SKIPPED_TARGET_NOT_EMPTY);

        assertThat(Files.readString(memoryFile()))
                .contains("新的记忆")
                .doesNotContain("旧的记忆");
        assertThat(legacyFile()).exists();
        assertThat(workspace.resolve(MemoryFiles.FILE_NAME + MemoryFiles.BACKUP_SUFFIX)).doesNotExist();
    }

    @Test
    void 备份名重复时加时间戳后缀_不覆盖旧备份() throws IOException {
        Files.writeString(legacyFile(), "旧的记忆\n");
        Path existingBackup = workspace.resolve(MemoryFiles.FILE_NAME + MemoryFiles.BACKUP_SUFFIX);
        Files.writeString(existingBackup, "上一次的备份\n");

        assertThat(MemoryFiles.migrateLegacy(workspace))
                .isEqualTo(MemoryFiles.LegacyMigrationResult.MIGRATED);

        try (Stream<Path> files = Files.list(workspace)) {
            assertThat(files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(MemoryFiles.FILE_NAME + MemoryFiles.BACKUP_SUFFIX + "-")))
                    .hasSize(1);
        }
        assertThat(Files.readString(existingBackup)).isEqualTo("上一次的备份\n");
    }
}
