package io.oryxos.storage.session;

import io.oryxos.core.session.Session;
import io.oryxos.storage.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * sessions 表 + {@link JpaSessionStore} 持久化验收测试。
 *
 * <p>覆盖 SC-004 的"重启后可读取"：建表走手工脚本 {@code db/schema.sql}；
 * 写入后强制 flush + clear（等价于重启后一级缓存清空），只允许从库读回。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan("io.oryxos.storage.entity")
@EnableJpaRepositories("io.oryxos.storage.repository")
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql"
})
class JpaSessionStoreTest {

    /**
     * 每次运行使用唯一的库文件，保证测的是当前 {@code db/schema.sql} 建出的全新库。
     *
     * <p>不复用固定文件名：Windows 下若上一次运行的 JVM/IDE 仍持有该 SQLite 文件句柄，
     * 删除会失败并直接让整个测试类报错，与环境耦合。</p>
     */
    @DynamicPropertySource
    static void sqliteDatabase(DynamicPropertyRegistry registry) {
        Path dbFile = Path.of("target", "jpa-session-store-test-" + UUID.randomUUID() + ".db")
                .toAbsolutePath();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile);
    }

    @SpringBootApplication
    static class TestApplication {
    }

    @Autowired
    private SessionRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    private JpaSessionStore store;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        store = new JpaSessionStore(repository);
    }

    private Session sample() {
        Session session = new Session("s-100", "ops-agent", "cli", "u-1");
        session.addMessage(new Session.Message("user", "帮我查一下今天天气"));
        session.addMessage(new Session.Message("assistant", "我先调用 get_weather"));
        session.addMessage(new Session.Message("tool", "晴天 25℃"));
        session.addMessage(new Session.Message("assistant", "建议穿短袖"));
        return session;
    }

    @Test
    void 手工脚本建表_会话与完整历史能存能读回() {
        store.save(sample());
        entityManager.flush();
        entityManager.clear();   // 模拟进程重启：一级缓存清空，只能从库读

        Optional<Session> found = store.find("s-100");

        assertThat(found).isPresent();
        Session loaded = found.get();
        assertThat(loaded.getSessionId()).isEqualTo("s-100");
        assertThat(loaded.getProfileName()).isEqualTo("ops-agent");
        assertThat(loaded.getChannel()).isEqualTo("cli");
        assertThat(loaded.getUserId()).isEqualTo("u-1");
        assertThat(loaded.getStatus()).isEqualTo("active");
        // 完整历史与顺序都必须还原
        assertThat(loaded.getMessages()).extracting(Session.Message::getRole)
                .containsExactly("user", "assistant", "tool", "assistant");
        assertThat(loaded.getMessages()).extracting(Session.Message::getContent)
                .containsExactly("帮我查一下今天天气", "我先调用 get_weather", "晴天 25℃", "建议穿短袖");
        // 时间列必须能正确往返（整数亲和性）
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getLastActiveAt()).isNotNull();
    }

    @Test
    void 读回后可以接续追加并再次持久化() {
        store.save(sample());
        entityManager.flush();
        entityManager.clear();

        Session resumed = store.find("s-100").orElseThrow();
        resumed.addMessage(new Session.Message("user", "那明天呢"));
        store.save(resumed);
        entityManager.flush();
        entityManager.clear();

        Session reloaded = store.find("s-100").orElseThrow();
        assertThat(reloaded.getMessages()).hasSize(5);
        assertThat(reloaded.getMessages().get(4).getContent()).isEqualTo("那明天呢");
        assertThat(repository.count()).isEqualTo(1);   // 更新而非新增
    }

    @Test
    void 不存在的会话返回空() {
        assertThat(store.find("no-such-session")).isEmpty();
    }
}
