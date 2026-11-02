package io.oryxos.boot;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleEncodingTest {

    /** 没有控制台（重定向、管道、Maven/IDE 捕获）时必须完全不干预，否则会破坏日志与测试输出。 */
    @Test
    void 接管行为与控制台存在性保持一致() {
        PrintStream original = System.out;
        boolean hasConsole = ConsoleEncoding.hasConsole();
        try {
            boolean installed = ConsoleEncoding.installConsoleStreams();
            assertThat(installed).isEqualTo(hasConsole);
            if (!hasConsole) {
                assertThat(System.out).isSameAs(original);
            }
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void 无控制台时不返回字符集因而不会改写日志编码() {
        if (ConsoleEncoding.hasConsole()) {
            assertThat(ConsoleEncoding.consoleCharset()).isNotNull();
        } else {
            assertThat(ConsoleEncoding.consoleCharset()).isNull();
        }
    }
}
