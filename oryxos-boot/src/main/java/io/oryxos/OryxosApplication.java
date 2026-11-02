package io.oryxos;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * OryxosApplication —— Spring Boot 配置入口。
 * 放在 io.oryxos 根包，确保所有子模块（io.oryxos.*）的 Bean 都能被组件扫描到。
 *
 * <p>JPA 的 Repository 与 Entity 默认只按主类所在包推导，这里显式声明扫描范围，
 * 避免跨模块（oryxos-storage）出现 "Found 0 JPA repository interfaces"。</p>
 *
 * <p>进程入口见 {@link OryxosLauncher}。</p>
 */
@SpringBootApplication
@EnableJpaRepositories(basePackages = "io.oryxos.storage.repository")
@EntityScan(basePackages = "io.oryxos.storage.entity")
public class OryxosApplication {
}
