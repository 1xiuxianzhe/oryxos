package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.session.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一门面的验收测试：只输出长期记忆（不重复会话历史）、读取永不抛、写后立即可见。
 */
class DefaultMemoryServiceTest {

    @TempDir
    Path workspace;

    private MemoryService service;

    @BeforeEach
    void setUp() {
        service = new DefaultMemoryService(new LongTermMemory(MemoryFiles.resolve(workspace)));
    }

    private Session session() {
        return new Session("s-1", "ops-agent", "cli", "u-1");
    }

    @Test
    void 门面输出核心区与归档区() {
        service.remember("用户叫小王", MemoryScope.CORE);
        service.remember("项目部署在 K8s", MemoryScope.ARCHIVAL);

        String context = service.buildContext(session());

        assertThat(context).contains("用户叫小王").contains("项目部署在 K8s");
    }

    @Test
    void 记忆为空时输出空串() {
        assertThat(service.buildContext(session())).isEmpty();
    }

    @Test
    void 路径异常时输出空串且不抛异常() throws IOException {
        Files.createDirectories(MemoryFiles.resolve(workspace));   // 目录冒充记忆文件

        assertThat(service.buildContext(session())).isEmpty();
    }

    @Test
    void 写入之后立即可见_无缓存() {
        service.remember("刚记的事", MemoryScope.ARCHIVAL);

        assertThat(service.buildContext(session())).contains("刚记的事");
        assertThat(service.recall("刚记的事")).isNotEmpty();
    }

    @Test
    void 门面不输出会话历史() {
        Session session = session();
        session.addMessage(new Session.Message("user", "这句是会话历史不该出现在记忆里"));
        service.remember("这是长期记忆", MemoryScope.ARCHIVAL);

        String context = service.buildContext(session);

        assertThat(context).contains("这是长期记忆");
        assertThat(context).doesNotContain("这句是会话历史不该出现在记忆里");
    }

    @Test
    void 空关键词检索返回空列表_不倒出全量() {
        service.remember("一条归档", MemoryScope.ARCHIVAL);

        assertThat(service.recall("  ")).isEmpty();
        assertThat(service.recall(null)).isEmpty();
    }

    @Test
    void 未命中返回空列表() {
        service.remember("一条归档", MemoryScope.ARCHIVAL);

        assertThat(service.recall("不存在的词")).isEmpty();
    }
}
