package io.oryxos.storage.repository;

import io.oryxos.storage.entity.LlmCallEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * llm_calls 审计表验收测试。
 *
 * <p>建表走手工脚本 {@code db/schema.sql}（而非 Hibernate 自动建表），验证脚本建出的表
 * 能存能读，且 {@code success}/{@code error_message} 两列真实存在——否则调用失败时
 * 这次事故在数据库里将完全没有痕迹。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan("io.oryxos.storage.entity")
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.sqlite.JDBC",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql"
})
class LlmCallRepositoryTest {

    /**
     * 上下文启动前先删掉旧库文件：SQLite 上 {@code CREATE TABLE IF NOT EXISTS}
     * 不会给已存在的表补列，沿用上一次运行留下的旧结构会让新列（如 {@code run_id}）
     * 直接变成 "no such column"。每个测试类都从全新库开始，测的就是当前 schema.sql。
     */
    @DynamicPropertySource
    static void sqliteDatabase(DynamicPropertyRegistry registry) {
        Path dbFile = Path.of("target", "llm-calls-test.db").toAbsolutePath();
        try {
            Files.deleteIfExists(dbFile);
        } catch (IOException e) {
            throw new IllegalStateException("无法清理测试数据库: " + dbFile, e);
        }
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile);
    }

    @SpringBootApplication
    static class TestApplication {
    }

    @Autowired
    private LlmCallRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    /** 强制 flush + clear，确保后续查询是真的走 DB 往返，而不是命中一级缓存。 */
    private void flushAndDetach() {
        entityManager.flush();
        entityManager.clear();
    }

    private LlmCallEntity sample(String callId, boolean success, String errorMessage) {
        LlmCallEntity entity = new LlmCallEntity();
        entity.setCallId(callId);
        entity.setSessionId("s-1");
        entity.setProvider("deepseek");
        entity.setModel("deepseek-chat");
        entity.setPromptTokens(10);
        entity.setCompletionTokens(20);
        entity.setTotalTokens(30);
        entity.setLatencyMs(123L);
        entity.setSuccess(success);
        entity.setStatus(success ? "SUCCESS" : "FAILED");
        entity.setErrorMessage(errorMessage);
        entity.setStartedAt(Instant.now());
        entity.setCompletedAt(Instant.now());
        return entity;
    }

    @Test
    void 手工脚本建表_成功记录能存能读() {
        repository.save(sample("call-1", true, null));
        flushAndDetach();

        List<LlmCallEntity> found = repository.findBySessionId("s-1");
        assertThat(found).hasSize(1);
        LlmCallEntity loaded = found.get(0);
        assertThat(loaded.getCallId()).isEqualTo("call-1");
        assertThat(loaded.getProvider()).isEqualTo("deepseek");
        assertThat(loaded.getModel()).isEqualTo("deepseek-chat");
        assertThat(loaded.getSuccess()).isTrue();
        assertThat(loaded.getErrorMessage()).isNull();
        assertThat(loaded.getTotalTokens()).isEqualTo(30);
        // 时间列必须能正确往返（SQLite 列亲和性不当会导致读回时时间戳解析失败）
        assertThat(loaded.getStartedAt()).isNotNull();
        assertThat(loaded.getCompletedAt()).isNotNull();
    }

    @Test
    void 手工脚本建表_失败记录包含error_message() {
        repository.save(sample("call-2", false, "connect timeout"));
        flushAndDetach();

        List<LlmCallEntity> found = repository.findBySessionId("s-1");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getSuccess()).isFalse();
        assertThat(found.get(0).getErrorMessage()).isEqualTo("connect timeout");
    }
}
