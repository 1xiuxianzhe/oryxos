package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LongTermMemory —— 长期记忆的核心读写，底层是 {@code <workspace>/memory/MEMORY.md} 一个 Markdown 文件。
 *
 * <p>文件由两个区块构成：</p>
 * <ul>
 *   <li><b>核心记忆</b>：始终在场，读全文时<b>完整</b>返回，不参与截断，也不参与检索（永远在场，无需检索）；</li>
 *   <li><b>归档记忆</b>：可检索、可截断（超出阈值只保留最近部分）。</li>
 * </ul>
 *
 * <p><b>三条刻意设计</b>：</p>
 * <ol>
 *   <li><b>不缓存</b>：每次都会重新读文件——写入之后下一轮就能看到，不需要失效机制；</li>
 *   <li><b>截断只在读取路径</b>：磁盘文件保持"只追加"，绝不因为上下文放不下就删掉用户数据；</li>
 *   <li><b>读取永不抛异常</b>：文件缺失、缺区块、路径是目录、IO 失败一律按空记忆处理——
 *       记忆只是上下文的一部分，不该拥有中断对话的否决权。</li>
 * </ol>
 */
public class LongTermMemory {

    private static final Logger logger = LoggerFactory.getLogger(LongTermMemory.class);

    /** 归档区的注入阈值：超出只保留最近的内容。 */
    public static final int MAX_ARCHIVE_CHARS = 4000;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String CONTINUATION_INDENT = "  ";
    private static final String SECTION_MARKER = "## ";

    private final Path memoryFile;
    private final Object writeLock = new Object();

    public LongTermMemory(Path memoryFile) {
        this.memoryFile = memoryFile;
    }

    /** 记忆文件的真实路径（用于日志与诊断）。 */
    public Path file() {
        return memoryFile;
    }

    /**
     * 追加一条记忆到指定区块。
     *
     * <p>写入是"读 - 改 - 写"，因此用实例锁串行化：多个会话并发调用 {@code save_memory} 时，
     * 不会出现互相覆盖或半条记录。</p>
     *
     * @param content 内容，空白内容会被拒绝
     * @param scope   目标区块；{@code null} 时按归档记忆处理
     */
    public void append(String content, MemoryScope scope) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        MemoryScope target = scope == null ? MemoryScope.ARCHIVAL : scope;
        String entry = render(content, LocalDate.now());

        synchronized (writeLock) {
            String updated = insertEntry(readRaw(), target, entry);
            writeFile(updated);
        }
    }

    /**
     * 加载用于注入上下文的长期记忆：核心区完整 + 归档区（必要时截断）。
     *
     * <p>文件里完全没有区块标题时（被人手工改坏），原样放入长期记忆段——宁可少一层分区，
     * 也不让内容凭空消失。</p>
     *
     * @return 记忆文本；无记忆时为空串
     */
    public String load() {
        String raw = readRaw();
        if (raw.isBlank()) {
            return "";
        }
        boolean hasAnySection = hasHeader(raw, MemoryFiles.CORE_HEADER)
                || hasHeader(raw, MemoryFiles.ARCHIVE_HEADER);
        if (!hasAnySection) {
            return truncateArchive(raw);
        }

        String core = section(raw, MemoryFiles.CORE_HEADER).strip();
        String archive = truncateArchive(section(raw, MemoryFiles.ARCHIVE_HEADER));

        StringBuilder result = new StringBuilder();
        if (!core.isEmpty()) {
            result.append(core);
        }
        if (!archive.isEmpty()) {
            if (result.length() > 0) {
                result.append("\n\n");
            }
            result.append(archive);
        }
        return result.toString();
    }

    /**
     * 在归档区按关键词检索（大小写不敏感）。
     *
     * <p>核心记忆永远在场，不在检索范围内；关键词为空白时返回空列表，
     * <b>不得</b>退化成"把整个归档区倒出来"。</p>
     */
    public List<String> recallByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String archive = section(readRaw(), MemoryFiles.ARCHIVE_HEADER);
        if (archive.isBlank()) {
            return List.of();
        }
        String needle = keyword.strip().toLowerCase(Locale.ROOT);
        return archive.lines()
                .map(String::stripTrailing)
                .filter(line -> !line.isBlank() && !line.startsWith(SECTION_MARKER))
                .filter(line -> line.toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }

    // ---------- 内部实现 ----------

    /** 渲染一条记忆：首行带日期前缀，其余行缩进续行（这样内容里的 "## " 不会变成新区块）。 */
    private static String render(String content, LocalDate date) {
        List<String> lines = content.strip().lines().toList();
        StringBuilder entry = new StringBuilder()
                .append("- [").append(date.format(DATE)).append("] ")
                .append(lines.get(0).strip());
        for (int i = 1; i < lines.size(); i++) {
            entry.append('\n').append(CONTINUATION_INDENT).append(lines.get(i).strip());
        }
        return entry.toString();
    }

    /** 把条目插入目标区块末尾；区块或文件缺失时先补骨架，不重排既有内容。 */
    private static String insertEntry(String raw, MemoryScope scope, String entry) {
        String header = scope == MemoryScope.CORE ? MemoryFiles.CORE_HEADER : MemoryFiles.ARCHIVE_HEADER;
        String base = raw == null ? "" : raw;
        if (base.isBlank()) {
            base = MemoryFiles.SKELETON;
        } else if (!hasHeader(base, header)) {
            base = base.stripTrailing() + "\n\n" + header + "\n";
        }

        List<String> lines = new ArrayList<>(base.lines().toList());
        int start = indexOfHeader(lines, header);
        int end = lines.size();
        for (int i = start + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith(SECTION_MARKER)) {
                end = i;
                break;
            }
        }

        int insertAt = end;
        while (insertAt > start + 1 && lines.get(insertAt - 1).isBlank()) {
            lines.remove(--insertAt);
        }
        List<String> entryLines = entry.lines().toList();
        lines.addAll(insertAt, entryLines);
        lines.add(insertAt + entryLines.size(), "");

        return String.join("\n", lines) + "\n";
    }

    private static boolean hasHeader(String text, String header) {
        return text.lines().anyMatch(line -> line.strip().equals(header));
    }

    private static int indexOfHeader(List<String> lines, String header) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(header)) {
                return i;
            }
        }
        return -1;
    }

    /** 取某个区块的正文（不含区块标题）；区块不存在时返回空串。 */
    private static String section(String raw, String header) {
        List<String> lines = raw.lines().toList();
        int start = indexOfHeader(lines, header);
        if (start < 0) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(SECTION_MARKER)) {
                break;
            }
            body.append(line).append('\n');
        }
        return body.toString();
    }

    /** 归档区超阈值时只保留最近的内容（核心区不走这里）。 */
    private static String truncateArchive(String archive) {
        String trimmed = archive == null ? "" : archive.strip();
        if (trimmed.length() <= MAX_ARCHIVE_CHARS) {
            return trimmed;
        }
        return trimmed.substring(trimmed.length() - MAX_ARCHIVE_CHARS);
    }

    /** 读取原始内容；任何读取失败都降级为空（含"文件不存在"与"路径其实是目录"）。 */
    private String readRaw() {
        try {
            if (!Files.isRegularFile(memoryFile)) {
                return "";
            }
            // 记不起文件是用什么编辑器改的，所以读进来先统一去掉可能存在的 UTF-8 BOM
            return MemoryFiles.stripBom(Files.readString(memoryFile, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            logger.warn("[Memory] 读取记忆文件失败，按空记忆处理: {}", memoryFile, e);
            return "";
        }
    }

    private void writeFile(String text) {
        try {
            Path parent = memoryFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(memoryFile, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("写入记忆文件失败: " + memoryFile, e);
        }
    }
}
