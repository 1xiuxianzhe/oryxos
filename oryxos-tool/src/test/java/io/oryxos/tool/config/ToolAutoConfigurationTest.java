package io.oryxos.tool.config;

import io.oryxos.core.tool.ToolRegistry;
import io.oryxos.tool.builtin.MockWeatherTool;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.sandbox.SandboxProperties;
import io.oryxos.tool.sandbox.WhitelistSandbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具体系装配：内置工具登记 + 沙箱配置校验。
 *
 * <p>见 {@code specs/007-sandbox/contracts/sandbox.md} 第 5 节验证项 11、16。</p>
 */
class ToolAutoConfigurationTest {

    private static Sandbox sandbox(Path allowed) {
        return new WhitelistSandbox(List.of(allowed.toString()), List.of("echo"), List.of("127.0.0.1"));
    }

    @Test
    void 登记器把六个内置工具注册进注册表(@TempDir Path dir) {
        ToolRegistry registry = new ToolRegistry();

        new ToolAutoConfiguration.BuiltinToolRegistration(registry, sandbox(dir)).afterPropertiesSet();

        assertThat(registry.listAll()).hasSize(6);
        assertThat(registry.contains("read_file")).isTrue();
        assertThat(registry.contains("write_file")).isTrue();
        assertThat(registry.contains("list_dir")).isTrue();
        assertThat(registry.contains("shell")).isTrue();
        assertThat(registry.contains("http_get")).isTrue();
        assertThat(registry.contains("http_post")).isTrue();
    }

    @Test
    void 六个内置工具的契约三件套都非空(@TempDir Path dir) {
        ToolRegistry registry = new ToolRegistry();
        new ToolAutoConfiguration.BuiltinToolRegistration(registry, sandbox(dir)).afterPropertiesSet();

        assertThat(registry.listAll()).allSatisfy(toolResult -> {
            assertThat(toolResult.getName()).isNotBlank();
            assertThat(toolResult.getDescription()).isNotBlank();
            assertThat(toolResult.getInputSchema()).isNotBlank();
        });
    }

    @Test
    void 演示用天气工具独立登记_不混进内置六件套() {
        ToolRegistry registry = new ToolRegistry();

        new ToolAutoConfiguration.WeatherToolRegistration(registry).afterPropertiesSet();

        assertThat(registry.listAll()).hasSize(1);
        assertThat(registry.contains(MockWeatherTool.NAME)).isTrue();
    }

    @Test
    void 空清单仍能装配出沙箱() {
        Sandbox sandbox = new ToolAutoConfiguration().sandbox(new SandboxProperties());

        assertThat(sandbox).isInstanceOf(WhitelistSandbox.class);
    }

    @Test
    void 沙箱Bean的类型是接口而非实现(@TempDir Path dir) {
        SandboxProperties properties = new SandboxProperties();
        properties.setAllowedPaths(List.of(dir.toString()));

        Sandbox sandbox = new ToolAutoConfiguration().sandbox(properties);

        // 四个 Tool 注入的静态类型是 Sandbox：换隔离档只换这里的实现类
        assertThat(sandbox).isInstanceOf(WhitelistSandbox.class);
    }

    @Test
    void 空白路径条目在启动阶段报错() {
        SandboxProperties properties = new SandboxProperties();
        // 空字符串路径会被规范化成当前工作目录，"配了个空值"等于"允许了工作目录下所有文件"
        properties.setAllowedPaths(List.of("  "));

        assertThatThrownBy(() -> new ToolAutoConfiguration().sandbox(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-paths");
    }

    @Test
    void 空白域名条目在启动阶段报错() {
        SandboxProperties properties = new SandboxProperties();
        properties.setAllowedDomains(List.of(""));

        assertThatThrownBy(() -> new ToolAutoConfiguration().sandbox(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-domains");
    }

    @Test
    void 空白命令条目在启动阶段报错() {
        SandboxProperties properties = new SandboxProperties();
        properties.setAllowedCommands(List.of("echo", ""));

        assertThatThrownBy(() -> new ToolAutoConfiguration().sandbox(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-commands");
    }

    // ---------- 关闭开关 ----------

    @Test
    void 默认启用沙箱校验() {
        // 默认必须是"开"：漏配导致敞开的代价远高于开发时多配几行
        WhitelistSandbox sandbox = (WhitelistSandbox) new ToolAutoConfiguration().sandbox(new SandboxProperties());

        assertThat(sandbox.isEnabled()).isTrue();
    }

    @Test
    void 显式关闭后三个维度一律放行(@TempDir Path dir) {
        SandboxProperties properties = new SandboxProperties();
        properties.setEnabled(false);
        properties.setAllowedPaths(List.of(dir.toString()));

        WhitelistSandbox sandbox = (WhitelistSandbox) new ToolAutoConfiguration().sandbox(properties);

        assertThat(sandbox.isEnabled()).isFalse();
        assertThatCode(() -> sandbox.enforce(new SandboxAction(
                ActionType.FILE_ACCESS, dir.getParent().resolve("outside.txt").toString())))
                .doesNotThrowAnyException();
        assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.SHELL_EXEC, "rm -rf /")))
                .doesNotThrowAnyException();
        assertThatCode(() -> sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, "https://evil.example.net")))
                .doesNotThrowAnyException();
    }

    @Test
    void 关闭开关不影响配置格式校验() {
        SandboxProperties properties = new SandboxProperties();
        properties.setEnabled(false);
        properties.setAllowedPaths(List.of("   "));

        // 配置缺陷与"当前是否启用"无关：越早发现越好，
        // 否则会在"上线才打开开关"的那一刻才炸——那时已经在生产上了
        assertThatThrownBy(() -> new ToolAutoConfiguration().sandbox(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-paths");
    }
}
