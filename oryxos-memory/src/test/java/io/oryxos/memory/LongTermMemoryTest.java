package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 长期记忆文件读写的验收 harness。
 *
 * <p>两个最值钱的回归：<b>写后立读（无缓存）</b>与<b>截断只裁归档、核心一字不动</b>——
 * 后者是"最容易被后来者优化掉"的一条，一旦有人把截断改成对整个文件掐头去尾，这里立刻红。</p>
 */
class LongTermMemoryTest {

    @TempDir
    Path workspace;

    private LongTermMemory memory() {
        return new LongTermMemory(MemoryFiles.resolve(workspace));
    }

    private String raw() throws IOException {
        return Files.readString(MemoryFiles.resolve(workspace), StandardCharsets.UTF_8);
    }

    // ---------- US1：把事记住并想起来 ----------

    @Test
    void 写入后立刻可读_不允许有缓存() {
        LongTermMemory memory = memory();

        memory.append("刚记的事", MemoryScope.ARCHIVAL);

        // 同一进程内下一次读取立即可见——不需要重启，也不需要等缓存刷新
        assertThat(memory.load()).contains("刚记的事");
        assertThat(memory.recallByKeyword("刚记的事")).isNotEmpty();
    }

    @Test
    void 首次写入自动建目录与骨架() throws IOException {
        memory().append("用户偏好中文", MemoryScope.ARCHIVAL);

        assertThat(Files.exists(MemoryFiles.resolve(workspace))).isTrue();
        assertThat(raw())
                .contains(MemoryFiles.TITLE)
                .contains(MemoryFiles.CORE_HEADER)
                .contains(MemoryFiles.ARCHIVE_HEADER);
    }

    @Test
    void 记忆文件缺失时加载为空() {
        assertThat(memory().load()).isEmpty();
        assertThat(memory().recallByKeyword("任意")).isEmpty();
    }

    @Test
    void 记忆路径其实是目录时_读取降级为空不抛异常() throws IOException {
        Files.createDirectories(MemoryFiles.resolve(workspace));

        assertThat(memory().load()).isEmpty();
        assertThat(memory().recallByKeyword("任意")).isEmpty();
    }

    @Test
    void 写入失败时抛异常_不静默吞掉() throws IOException {
        Files.writeString(workspace.resolve(MemoryFiles.DIR_NAME), "占位：父路径是文件");

        assertThatThrownBy(() -> memory().append("写不进去", MemoryScope.ARCHIVAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入记忆文件失败");
    }

    @Test
    void 空白内容被拒绝() {
        assertThatThrownBy(() -> memory().append("   ", MemoryScope.ARCHIVAL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- US3：核心记忆永远在场 ----------

    @Test
    void 截断只裁归档区_核心记忆一字不能少() throws IOException {
        LongTermMemory memory = memory();
        memory.append("用户叫小王，偏好用 Java", MemoryScope.CORE);
        for (int i = 0; i < 500; i++) {
            memory.append("归档流水 " + i, MemoryScope.ARCHIVAL);
        }

        String loaded = memory.load();

        assertThat(loaded).contains("用户叫小王，偏好用 Java");   // 核心区完整——"始终在场"的底线
        assertThat(loaded).doesNotContain("归档流水 0");          // 归档区最早的被裁掉
        assertThat(loaded).contains("归档流水 499");              // 保留的是最近的
    }

    @Test
    void 截断不落盘_磁盘文件保持只追加() throws IOException {
        LongTermMemory memory = memory();
        memory.append("核心一条", MemoryScope.CORE);
        for (int i = 0; i < 500; i++) {
            memory.append("归档流水 " + i, MemoryScope.ARCHIVAL);
        }
        String before = raw();

        memory.load();

        // 截断只作用于"注入上下文"，不允许顺手删掉用户数据
        assertThat(raw()).isEqualTo(before);
    }

    @Test
    void scope路由_核心写核心区_归档写归档区() throws IOException {
        LongTermMemory memory = memory();
        memory.append("写进核心", MemoryScope.CORE);
        memory.append("写进归档", MemoryScope.ARCHIVAL);

        String content = raw();
        int coreHeader = content.indexOf(MemoryFiles.CORE_HEADER);
        int archiveHeader = content.indexOf(MemoryFiles.ARCHIVE_HEADER);

        assertThat(coreHeader).isLessThan(archiveHeader);
        assertThat(content.substring(coreHeader, archiveHeader))
                .contains("写进核心")
                .doesNotContain("写进归档");
        assertThat(content.substring(archiveHeader)).contains("写进归档");
    }

    @Test
    void scope缺省时落归档区() throws IOException {
        memory().append("没写 scope 的一条", null);

        assertThat(raw().substring(raw().indexOf(MemoryFiles.ARCHIVE_HEADER))).contains("没写 scope 的一条");
    }

    @Test
    void 检索只搜归档区_核心记忆不参与检索() {
        LongTermMemory memory = memory();
        memory.append("核心：用户在私有部署环境", MemoryScope.CORE);
        memory.append("归档：项目部署在 K8s", MemoryScope.ARCHIVAL);

        assertThat(memory.recallByKeyword("K8s")).hasSize(1);
        assertThat(memory.recallByKeyword("私有部署")).isEmpty();
    }

    @Test
    void 检索大小写不敏感_空关键词不倒出全量() {
        LongTermMemory memory = memory();
        memory.append("项目用 Spring Boot", MemoryScope.ARCHIVAL);

        assertThat(memory.recallByKeyword("spring boot")).hasSize(1);
        assertThat(memory.recallByKeyword("   ")).isEmpty();
        assertThat(memory.recallByKeyword(null)).isEmpty();
    }

    @Test
    void 多行内容与标题符号不破坏区块结构() throws IOException {
        LongTermMemory memory = memory();
        memory.append("第一行\n## 归档记忆\n第三行", MemoryScope.ARCHIVAL);
        memory.append("核心一条", MemoryScope.CORE);

        String content = raw();

        // 行首才算区块标题：内容里的 "## 归档记忆" 被缩进续行，不能变成新区块
        assertThat(countOccurrences(content, "\n" + MemoryFiles.ARCHIVE_HEADER)).isEqualTo(1);
        assertThat(countOccurrences(content, "\n" + MemoryFiles.CORE_HEADER)).isEqualTo(1);
        assertThat(memory.load()).contains("第一行").contains("核心一条");
        assertThat(memory.recallByKeyword("第三行")).hasSize(1);
    }

    @Test
    void 脏文件容错_没有区块标题时整篇仍能注入() throws IOException {
        Files.createDirectories(workspace.resolve(MemoryFiles.DIR_NAME));
        Files.writeString(MemoryFiles.resolve(workspace), "手写的自由文本记忆\n");

        assertThat(memory().load()).contains("手写的自由文本记忆");
    }

    @Test
    void 脏文件容错_只有一个区块时另一区为空且能补齐() throws IOException {
        Files.createDirectories(workspace.resolve(MemoryFiles.DIR_NAME));
        Files.writeString(MemoryFiles.resolve(workspace),
                MemoryFiles.TITLE + "\n\n" + MemoryFiles.ARCHIVE_HEADER + "\n- [2026-10-16] 只有归档\n");

        LongTermMemory memory = memory();
        assertThat(memory.load()).contains("只有归档");

        memory.append("补一条核心", MemoryScope.CORE);

        assertThat(raw()).contains(MemoryFiles.CORE_HEADER);
        assertThat(memory.load()).contains("补一条核心").contains("只有归档");
    }

    @Test
    void 带BOM的记忆文件仍能识别区块() throws IOException {
        Files.createDirectories(workspace.resolve(MemoryFiles.DIR_NAME));
        Files.writeString(MemoryFiles.resolve(workspace), "\uFEFF" + MemoryFiles.SKELETON
                + "- [2026-10-16] 用户偏好中文\n");

        assertThat(memory().load()).contains("用户偏好中文").doesNotContain("\uFEFF");
        assertThat(memory().recallByKeyword("用户偏好")).hasSize(1);
    }

    @Test
    void 脏文件容错_区块顺序颠倒时按标题定位() throws IOException {
        Files.createDirectories(workspace.resolve(MemoryFiles.DIR_NAME));
        Files.writeString(MemoryFiles.resolve(workspace),
                MemoryFiles.ARCHIVE_HEADER + "\n- [2026-10-16] 归档在前\n"
                        + MemoryFiles.CORE_HEADER + "\n- [2026-10-16] 核心在后\n");

        LongTermMemory memory = memory();

        assertThat(memory.load()).contains("核心在后").contains("归档在前");
        assertThat(memory.recallByKeyword("归档在前")).hasSize(1);
        assertThat(memory.recallByKeyword("核心在后")).isEmpty();
    }

    @Test
    void 并发写入不丢内容() throws Exception {
        LongTermMemory memory = memory();
        int threads = 8;
        int perThread = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                int id = t;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            memory.append("并发-" + id + "-" + i, MemoryScope.ARCHIVAL);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        String content = raw();
        for (int t = 0; t < threads; t++) {
            for (int i = 0; i < perThread; i++) {
                assertThat(content).contains("并发-" + t + "-" + i);
            }
        }
        // 条目不重不漏：8 × 20 条
        assertThat(countOccurrences(content, "- [")).isEqualTo(threads * perThread);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
