package io.oryxos.boot;

import java.io.Console;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Windows 控制台编码对齐：让全部控制台输出不依赖代码页。
 *
 * <p>问题背景：{@code System.out.println(String)} 由 {@code PrintStream} 自己的 charset 编码，
 * Windows 中文系统上该 charset 取自 ANSI 代码页（通常是 GBK）。当控制台代码页与它不一致时
 * 中文就会乱码；而 Logback 是按字节写 {@code System.out} 的，两条路径表现还不一样。</p>
 *
 * <p>解决思路：检测到真实交互式控制台时，把 {@code System.out}/{@code System.err} 换成
 * 基于 {@link Console} 的输出——Windows 上它底层是 {@code WriteConsoleW} 宽字符 API，
 * 直接传 UTF-16，不受代码页影响。重定向/管道/IDE 捕获时 {@code System.console()} 为 null，
 * 保持 JVM 原样，不做任何改动。</p>
 */
public final class ConsoleEncoding {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("windows");

    private static final int UTF8_CODE_PAGE = 65001;

    private ConsoleEncoding() {
    }

    /** 是否存在真实交互式控制台；重定向、管道或 IDE 捕获时为 false。 */
    public static boolean hasConsole() {
        return System.console() != null;
    }

    /**
     * 有控制台时把标准输出/错误重定向到宽字符通道。
     *
     * <p>必须在 Spring 启动之前调用：Logback 的 ConsoleAppender 在初始化时会捕获
     * 当时的 {@code System.out}，之后再换就来不及了。</p>
     *
     * @return true 表示已接管标准流
     */
    public static boolean installConsoleStreams() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        Writer writer = console.writer();
        // PrintStream 统一按 UTF-8 编码，WriterOutputStream 再按 UTF-8 还原成字符交给控制台
        System.setOut(new PrintStream(new WriterOutputStream(writer), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new WriterOutputStream(writer), true, StandardCharsets.UTF_8));
        return true;
    }

    /** 控制台当前字符集；非 Windows 或无控制台时返回 {@code null}（表示无需干预）。 */
    public static Charset consoleCharset() {
        if (!WINDOWS || !hasConsole()) {
            return null;
        }
        int codePage = activeCodePage();
        if (codePage <= 0 || codePage == UTF8_CODE_PAGE) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName("cp" + codePage);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** 读取当前控制台活动代码页（如 936 / 65001）；失败返回 -1。 */
    private static int activeCodePage() {
        try {
            Process process = new ProcessBuilder("cmd", "/c", "chcp")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            process.waitFor();
            int colon = output.lastIndexOf(':');
            if (colon < 0) {
                return -1;
            }
            return Integer.parseInt(output.substring(colon + 1).replace(".", "").trim());
        } catch (IOException | InterruptedException | NumberFormatException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    /** 把字节按 UTF-8 还原成字符，再交给控制台的宽字符通道写出。 */
    private static final class WriterOutputStream extends OutputStream {

        private final Writer writer;

        private WriterOutputStream(Writer writer) {
            this.writer = writer;
        }

        @Override
        public void write(int b) throws IOException {
            writer.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            writer.write(new String(bytes, offset, length, StandardCharsets.UTF_8));
        }

        @Override
        public void flush() throws IOException {
            writer.flush();
        }

        @Override
        public void close() throws IOException {
            writer.flush();
        }
    }
}
