package io.oryxos.storage.repository;

import io.oryxos.storage.entity.ToolInvocationEntity;
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
 * tool_invocations 审计表验收测试。
 *
 * <p>建表走手工脚本 {@code db/schema.sql}（而非 Hibernate 自动建表），并强制 flush + clear
 * 走真实 DB 往返，验证 {@code success} / {@code error_message} 两列真实存在且时间列可正确读回。</p>
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
class ToolInvocationRepositoryTest {

    /**
     * 上下文启动前先删掉旧库文件：SQLite 上 {@code CREATE TABLE IF NOT EXISTS}
     * 不会给已存在的表补列，沿用上一次运行留下的旧结构会让新列（如 {@code run_id}）
     * 直接变成 "no such column"。每个测试类都从全新库开始，测的就是当前 schema.sql。
     */
    @DynamicPropertySource
    static void sqliteDatabase(DynamicPropertyRegistry registry) {
        Path dbFile = Path.of("target", "tool-invocations-test.db").toAbsolutePath();
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
    private ToolInvocationRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    private ToolInvocationEntity sample(String id, boolean success, String errorMessage) {
        ToolInvocationEntity entity = new ToolInvocationEntity();
        entity.setInvocationId(id);
        entity.setSessionId("s-1");
        entity.setProfileName("ops-agent");
        entity.setToolName("http_get");
        entity.setParameters("{\"url\":\"http://weather\"}");
        entity.setStatus(success ? "SUCCESS" : "FAILED");
        entity.setSuccess(success);
        entity.setErrorMessage(errorMessage);
        entity.setResult(success ? "晴天 25℃" : null);
        entity.setLatencyMs(42L);
        entity.setStartedAt(Instant.now());
        entity.setCompletedAt(Instant.now());
        return entity;
    }

    @Test
    void 手工脚本建表_成功记录能存能读() {
        repository.save(sample("inv-1", true, null));
        entityManager.flush();
        entityManager.clear();

        List<ToolInvocationEntity> found = repository.findBySessionId("s-1");
        assertThat(found).hasSize(1);
        ToolInvocationEntity loaded = found.get(0);
        assertThat(loaded.getToolName()).isEqualTo("http_get");
        assertThat(loaded.getProfileName()).isEqualTo("ops-agent");
        assertThat(loaded.getSuccess()).isTrue();
        assertThat(loaded.getErrorMessage()).isNull();
        assertThat(loaded.getLatencyMs()).isEqualTo(42L);
        // 时间列必须能正确往返（整数亲和性）
        assertThat(loaded.getStartedAt()).isNotNull();
        assertThat(loaded.getCompletedAt()).isNotNull();
    }

    @Test
    void 手工脚本建表_失败记录包含error_message() {
        repository.save(sample("inv-2", false, "域名不在白名单内"));
        entityManager.flush();
        entityManager.clear();

        List<ToolInvocationEntity> found = repository.findBySessionId("s-1");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getSuccess()).isFalse();
        assertThat(found.get(0).getErrorMessage()).isEqualTo("域名不在白名单内");
        assertThat(found.get(0).getStatus()).isEqualTo("FAILED");
    }
}
