package io.oryxos.tool.sandbox;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.oryxos.tool.sandbox.ActionType.FILE_ACCESS;
import static io.oryxos.tool.sandbox.ActionType.HTTP_REQUEST;
import static io.oryxos.tool.sandbox.ActionType.SHELL_EXEC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 沙箱契约：三维度各自的放行与拒绝、空清单即全拒、经典绕过手法逐个被拦。
 *
 * <p>见 {@code specs/007-sandbox/contracts/sandbox.md} 第 5 节验证项 1 ~ 11。</p>
 *
 * <p>本类由 005 的 {@code SandboxCheckerTest} 改写而来：用例逐条保留（那些是实测后修出来的行为），
 * 并补充命令空白/大小写、域名形似、空目标四类固定行为。</p>
 */
class WhitelistSandboxTest {

    private static WhitelistSandbox sandbox(List<String> paths, List<String> commands, List<String> domains) {
        return new WhitelistSandbox(paths, commands, domains);
    }

    private static SandboxAction file(String path) {
        return new SandboxAction(FILE_ACCESS, path);
    }

    private static SandboxAction shell(String command) {
        return new SandboxAction(SHELL_EXEC, command);
    }

    private static SandboxAction http(String url) {
        return new SandboxAction(HTTP_REQUEST, url);
    }

    // ---------- 文件维度 ----------

    @Test
    void 路径在白名单内放行(@TempDir Path allowed) {
        Path inside = allowed.resolve("note.txt");

        assertThatCode(() -> sandbox(List.of(allowed.toString()), List.of(), List.of())
                .enforce(file(inside.toString())))
                .doesNotThrowAnyException();
    }

    @Test
    void 路径在白名单外被拒(@TempDir Path allowed, @TempDir Path outside) {
        Path target = outside.resolve("secret.txt");

        assertThatThrownBy(() -> sandbox(List.of(allowed.toString()), List.of(), List.of())
                .enforce(file(target.toString())))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("白名单")
                .hasMessageContaining(target.toString());
    }

    @Test
    void 用上级目录绕过被拒(@TempDir Path allowed) {
        // allowed/../sneaky.txt 规范化后落在 allowed 的父目录，不该被放行
        String sneaky = allowed.resolve("..").resolve("sneaky.txt").toString();

        assertThatThrownBy(() -> sandbox(List.of(allowed.toString()), List.of(), List.of())
                .enforce(file(sneaky)))
                .isInstanceOf(SandboxViolationException.class);
    }

    @Test
    void 路径清单为空时一律拒绝(@TempDir Path any) {
        assertThatThrownBy(() -> sandbox(List.of(), List.of(), List.of())
                .enforce(file(any.resolve("f.txt").toString())))
                .isInstanceOf(SandboxViolationException.class);
    }

    // ---------- 命令维度 ----------

    @Test
    void 命令在白名单内放行() {
        assertThatCode(() -> sandbox(List.of(), List.of("echo"), List.of())
                .enforce(shell("echo hello")))
                .doesNotThrowAnyException();
    }

    @Test
    void 命令前导空白不影响取首token() {
        assertThatCode(() -> sandbox(List.of(), List.of("echo"), List.of())
                .enforce(shell("   echo hello")))
                .doesNotThrowAnyException();
    }

    @Test
    void 命令大小写变体被拒() {
        // fail-closed：放宽大小写等于扩大白名单。代价是 ECHO 被误拒，已在 quickstart 写明
        assertThatThrownBy(() -> sandbox(List.of(), List.of("echo"), List.of())
                .enforce(shell("ECHO hello")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("ECHO");
    }

    @Test
    void 命令不在白名单时按首个词拒绝() {
        assertThatThrownBy(() -> sandbox(List.of(), List.of("echo"), List.of())
                .enforce(shell("rm -rf /")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("rm");
    }

    @Test
    void 命令清单为空时一律拒绝() {
        assertThatThrownBy(() -> sandbox(List.of(), List.of(), List.of())
                .enforce(shell("echo hello")))
                .isInstanceOf(SandboxViolationException.class);
    }

    // ---------- 域名维度 ----------

    @Test
    void 域名在白名单内放行() {
        assertThatCode(() -> sandbox(List.of(), List.of(), List.of("api.example.com"))
                .enforce(http("https://api.example.com/v1/chat")))
                .doesNotThrowAnyException();
    }

    @Test
    void 域名支持通配() {
        assertThatCode(() -> sandbox(List.of(), List.of(), List.of("*.example.com"))
                .enforce(http("https://api.example.com/v1")))
                .doesNotThrowAnyException();
    }

    @Test
    void 带端口的地址按host判定() {
        assertThatCode(() -> sandbox(List.of(), List.of(), List.of("api.example.com"))
                .enforce(http("https://api.example.com:8443/v1")))
                .doesNotThrowAnyException();
    }

    @Test
    void 域名不在白名单被拒() {
        assertThatThrownBy(() -> sandbox(List.of(), List.of(), List.of("api.example.com"))
                .enforce(http("https://evil.example.net/steal")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("evil.example.net");
    }

    @Test
    void 通配符域名_不能被形似域名绕过() {
        // evil-example.com 以 "example.com" 结尾，但不是子域——endsWith 实现会在这里破功
        assertThatThrownBy(() -> sandbox(List.of(), List.of(), List.of("*.example.com"))
                .enforce(http("https://evil-example.com/x")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("evil-example.com");
    }

    @Test
    void 通配不跨域匹配() {
        // *.example.com 不应匹配 example.com.evil.net 这类后缀伪造
        assertThatThrownBy(() -> sandbox(List.of(), List.of(), List.of("*.example.com"))
                .enforce(http("https://example.com.evil.net/")))
                .isInstanceOf(SandboxViolationException.class);
    }

    @Test
    void 域名清单为空时一律拒绝() {
        assertThatThrownBy(() -> sandbox(List.of(), List.of(), List.of())
                .enforce(http("https://api.example.com")))
                .isInstanceOf(SandboxViolationException.class);
    }

    // ---------- 动作本身非法 ----------

    @Test
    void 目标为空白时拒绝而不是抛NPE() {
        WhitelistSandbox sandbox = sandbox(List.of(), List.of(), List.of());

        assertThatThrownBy(() -> sandbox.enforce(file("   ")))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("目标为空");
        assertThatThrownBy(() -> sandbox.enforce(shell(null)))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("目标为空");
        assertThatThrownBy(() -> sandbox.enforce(null))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("类型");
    }

    // ---------- 关闭开关：三个维度一律放行 ----------

    @Test
    void 关闭校验时三个维度全部放行_即使清单为空(@TempDir Path any) {
        WhitelistSandbox disabled = new WhitelistSandbox(List.of(), List.of(), List.of(), false);

        // 空清单 + 关闭：按"空即全拒"本应全部拒绝，关闭后必须一律放行
        assertThatCode(() -> disabled.enforce(file(any.resolve("f.txt").toString())))
                .doesNotThrowAnyException();
        assertThatCode(() -> disabled.enforce(shell("rm -rf /")))
                .doesNotThrowAnyException();
        assertThatCode(() -> disabled.enforce(http("https://evil.example.net/steal")))
                .doesNotThrowAnyException();
    }

    @Test
    void 默认启用校验_不传开关时仍然拦截(@TempDir Path any) {
        WhitelistSandbox enabledByDefault = new WhitelistSandbox(List.of(), List.of(), List.of());

        assertThat(enabledByDefault.isEnabled()).isTrue();
        assertThatThrownBy(() -> enabledByDefault.enforce(file(any.resolve("f.txt").toString())))
                .isInstanceOf(SandboxViolationException.class);
    }

    @Test
    void 关闭校验时越界符号链接也放行() {
        // 关闭状态下不应有任何路径解析开销或判断——直接返回
        WhitelistSandbox disabled = new WhitelistSandbox(List.of(), List.of(), List.of(), false);

        assertThatCode(() -> disabled.enforce(file("C:\\totally\\outside\\place.txt")))
                .doesNotThrowAnyException();
    }

    // ---------- 符号链接：必须按真实路径判定 ----------

    @Test
    void 符号链接指向白名单外时被拒(@TempDir Path root) throws IOException {
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        Path secret = Files.createDirectories(root.resolve("secret"));
        Files.writeString(secret.resolve("passwords.txt"), "TOP SECRET");
        Path link = allowed.resolve("link");
        createSymlinkOrSkip(link, secret);

        Path target = link.resolve("passwords.txt");

        // 这个路径字面上落在 allowed 内，但它指向 allowed 之外：
        // 只做字面归一化会放行（已实测确认），按真实路径判定才会拒绝
        assertThatThrownBy(() -> sandbox(List.of(allowed.toString()), List.of(), List.of())
                .enforce(file(target.toString())))
                .isInstanceOf(SandboxViolationException.class);
    }

    @Test
    void 白名单目录自身是符号链接时按真实路径匹配(@TempDir Path root) throws IOException {
        Path real = Files.createDirectories(root.resolve("real-dir"));
        Path linkDir = root.resolve("link-dir");
        createSymlinkOrSkip(linkDir, real);

        Path note = real.resolve("note.txt");
        Files.writeString(note, "内容");

        // 配置里写的是链接目录、文件实际在真实目录下：两侧口径一致才匹配得上
        assertThatCode(() -> sandbox(List.of(linkDir.toString()), List.of(), List.of())
                .enforce(file(note.toString())))
                .doesNotThrowAnyException();
    }

    @Test
    void 新建文件时的路径校验同样能识别中间层符号链接(@TempDir Path root) throws IOException {
        Path allowed = Files.createDirectories(root.resolve("allowed"));
        Path outside = Files.createDirectories(root.resolve("outside"));
        Path link = allowed.resolve("link");
        createSymlinkOrSkip(link, outside);

        // 目标文件还不存在（写入新文件），但中间的 link 已存在且指向白名单外
        Path target = link.resolve("brand-new.txt");

        assertThatThrownBy(() -> sandbox(List.of(allowed.toString()), List.of(), List.of())
                .enforce(file(target.toString())))
                .isInstanceOf(SandboxViolationException.class);
    }

    /**
     * Windows 上创建符号链接需要权限（或开发者模式）。
     * 环境不支持时<b>中止</b>用例而不是让它通过——否则"没测到"会被读成"测过了没问题"。
     */
    private static void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("当前环境无法创建符号链接，跳过该用例: " + e.getMessage());
        }
    }
}
