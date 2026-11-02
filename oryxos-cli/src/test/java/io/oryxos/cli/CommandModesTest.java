package io.oryxos.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandModesTest {

    @Test
    void 轻命令不启动Spring() {
        assertThat(CommandModes.isCliInvocation(new String[]{"init"})).isTrue();
        assertThat(CommandModes.mode(new String[]{"init"})).isEqualTo(CommandMode.LIGHT);
        assertThat(CommandModes.mode(new String[]{"profile", "list"})).isEqualTo(CommandMode.LIGHT);
        assertThat(CommandModes.mode(new String[]{"provider", "list"})).isEqualTo(CommandMode.LIGHT);
        assertThat(CommandModes.mode(new String[]{"session", "list"})).isEqualTo(CommandMode.LIGHT);
        assertThat(CommandModes.mode(new String[]{"status"})).isEqualTo(CommandMode.LIGHT);
        assertThat(CommandModes.mode(new String[]{"--help"})).isEqualTo(CommandMode.LIGHT);
    }

    @Test
    void 重命令启动Spring() {
        assertThat(CommandModes.mode(new String[]{"chat", "--profile", "default"}))
                .isEqualTo(CommandMode.HEAVY);
        assertThat(CommandModes.mode(new String[]{"serve", "--port", "9090"}))
                .isEqualTo(CommandMode.HEAVY);
        assertThat(CommandModes.mode(new String[]{"gateway"})).isEqualTo(CommandMode.HEAVY);
        // 工具清单必须来自真实注册表，而注册表只在运行时里存在
        assertThat(CommandModes.mode(new String[]{"tool", "list"})).isEqualTo(CommandMode.HEAVY);
    }

    @Test
    void 常驻命令和Web判定() {
        assertThat(CommandModes.isDaemon(new String[]{"serve"})).isTrue();
        assertThat(CommandModes.isDaemon(new String[]{"gateway"})).isTrue();
        assertThat(CommandModes.isDaemon(new String[]{"chat"})).isFalse();
        assertThat(CommandModes.isDaemon(new String[]{"tool", "list"})).isFalse();
        assertThat(CommandModes.requiresWebServer(new String[]{"serve"})).isTrue();
        assertThat(CommandModes.requiresWebServer(new String[]{"chat"})).isFalse();
        assertThat(CommandModes.requiresWebServer(new String[]{"tool", "list"})).isFalse();
    }

    @Test
    void 普通Spring启动不当作CLI调用() {
        assertThat(CommandModes.isCliInvocation(new String[]{})).isFalse();
        assertThat(CommandModes.isCliInvocation(null)).isFalse();
        assertThat(CommandModes.isCliInvocation(new String[]{"--spring.profiles.active=dev"})).isFalse();
        assertThat(CommandModes.isCliInvocation(new String[]{"unknown"})).isFalse();
    }
}
