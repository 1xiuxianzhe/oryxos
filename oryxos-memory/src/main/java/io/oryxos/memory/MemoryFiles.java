package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * MemoryFiles —— 记忆文件"长什么样、放在哪"的唯一真身。
 *
 * <p>运行时（{@code LongTermMemory}）与工作区初始化（{@code oryxos init}）都必须从这里取路径、
 * 骨架与区块标题：两处各写一遍格式，就会出现"init 建的文件运行时读不出区块"这类隐性缺陷，
 * 而且很难在单测里暴露。</p>
 */
public final class MemoryFiles {

    public static final String DIR_NAME = "memory";
    public static final String FILE_NAME = "MEMORY.md";

    /** 文件首行标题。 */
    public static final String TITLE = "# OryxOS Memory";
    /** 核心记忆区块标题（始终在场、不检索、不截断）。 */
    public static final String CORE_HEADER = "## 核心记忆";
    /** 归档记忆区块标题（可检索、可截断）。 */
    public static final String ARCHIVE_HEADER = "## 归档记忆";

    /** 文件骨架：两个区块标题必须先摆好，写入才不用猜位置。 */
    public static final String SKELETON =
            TITLE + "\n\n" + CORE_HEADER + "\n\n" + ARCHIVE_HEADER + "\n";

    /** 旧位置的文件名（工作区根目录），仅用于迁移识别。 */
    public static final String LEGACY_FILE_NAME = FILE_NAME;
    /** 迁移后遗留文件的备份后缀。 */
    public static final String BACKUP_SUFFIX = ".bak";

    private static final DateTimeFormatter BACKUP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final Logger logger = LoggerFactory.getLogger(MemoryFiles.class);

    private MemoryFiles() {
    }

    /** 规范路径：{@code <workspace>/memory/MEMORY.md}。 */
    public static Path resolve(Path workspace) {
        return workspace.resolve(DIR_NAME).resolve(FILE_NAME);
    }

    /** 旧位置：{@code <workspace>/MEMORY.md}（仅用于迁移识别，运行时不读它）。 */
    public static Path legacyFile(Path workspace) {
        return workspace.resolve(LEGACY_FILE_NAME);
    }

    /**
     * 幂等创建记忆文件：文件不存在时写入骨架，已存在时一字不动（不覆盖、不清空）。
     */
    public static void initialize(Path workspace) throws IOException {
        Path file = resolve(workspace);
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.notExists(file)) {
            Files.writeString(file, SKELETON, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    /**
     * 把旧位置（工作区根）的记忆文件迁移到规范路径。
     *
     * <p>迁移的边界是"绝不覆盖已有内容，也绝不做可能丢信息的操作"：
     * 旧文件没有有效内容时原样保留；规范位置已有内容时直接跳过并告警。</p>
     *
     * @return 本次迁移的结果（用于日志与测试断言）
     */
    public static LegacyMigrationResult migrateLegacy(Path workspace) throws IOException {
        Path legacy = legacyFile(workspace);
        if (!Files.isRegularFile(legacy)) {
            return LegacyMigrationResult.NO_LEGACY;
        }

        List<String> entries = effectiveLines(stripBom(Files.readString(legacy, StandardCharsets.UTF_8)));
        if (entries.isEmpty()) {
            logger.info("[Memory] 旧位置 {} 无有效记忆内容，未做迁移（骨架创建由 initialize 负责）", legacy);
            return LegacyMigrationResult.NOTHING_TO_MIGRATE;
        }

        initialize(workspace);
        Path target = resolve(workspace);
        String current = stripBom(Files.readString(target, StandardCharsets.UTF_8));
        if (!effectiveLines(current).isEmpty()) {
            logger.warn("[Memory] 规范位置 {} 已有记忆内容，跳过迁移；旧文件 {} 保留待人工处理",
                    target, legacy);
            return LegacyMigrationResult.SKIPPED_TARGET_NOT_EMPTY;
        }

        LongTermMemory memory = new LongTermMemory(target);
        for (String entry : entries) {
            memory.append(entry, MemoryScope.ARCHIVAL);
        }

        Path backup = backupPathFor(legacy);
        Files.move(legacy, backup);
        logger.info("[Memory] 已把旧位置 {} 的 {} 条记忆迁移到 {}，旧文件备份为 {}",
                legacy, entries.size(), target, backup);
        return LegacyMigrationResult.MIGRATED;
    }

    /**
     * 有效内容判定：忽略空行与以 {@code #} 开头的行后仍有非空行。
     *
     * <p>骨架与模板文件都只由标题行组成，因此用同一条规则就能区分"有内容的旧文件"与"空壳旧文件"。</p>
     */
    static List<String> effectiveLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : stripBom(text).lines().toList()) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            result.add(trimmed);
        }
        return result;
    }

    /**
     * 去掉开头的 UTF-8 BOM。
     *
     * <p>Windows 记事本、PowerShell 写出的 UTF-8 文件常常带 BOM（{@code U+FEFF}）。
     * 它既不是空白也不是 {@code #}，不去掉就会把首行的区块标题判成一条普通记忆——
     * 手工改过记忆文件的用户几乎必然会踩到。</p>
     */
    public static String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    private static Path backupPathFor(Path legacy) {
        Path backup = legacy.resolveSibling(legacy.getFileName() + BACKUP_SUFFIX);
        if (Files.exists(backup)) {
            backup = legacy.resolveSibling(legacy.getFileName() + BACKUP_SUFFIX + "-"
                    + LocalDateTime.now().format(BACKUP_TIMESTAMP));
        }
        return backup;
    }

    /** 迁移结果。 */
    public enum LegacyMigrationResult {
        /** 旧位置没有文件。 */
        NO_LEGACY,
        /** 旧位置有文件但没有有效内容（只有标题/空白）。 */
        NOTHING_TO_MIGRATE,
        /** 已迁移，旧文件改名备份。 */
        MIGRATED,
        /** 规范位置已有内容，跳过迁移（旧文件保留）。 */
        SKIPPED_TARGET_NOT_EMPTY
    }
}
