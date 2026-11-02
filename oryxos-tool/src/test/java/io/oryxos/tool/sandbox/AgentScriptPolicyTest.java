package io.oryxos.tool.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 目录 Agent 的脚本边界：只允许"解释器 + 本目录 scripts/"这一种形态。
 *
 * <p>重点不是"允许什么"，而是"拒绝时有没有把每一种绕过方式都堵住"：
 * 跨目录、内联代码、元字符串联、非白名单解释器。</p>
 */
class AgentScriptPolicyTest {

    @TempDir
    Path workspace;

    private Path agentDir;
    private Path script;
    private WhitelistSandbox sandbox;

    @BeforeEach
    void setUp() throws IOException {
        agentDir = workspace.resolve("agents").resolve("demo");
        Files.createDirectories(agentDir.resolve("scripts"));
        script = agentDir.resolve("scripts").resolve("ok.py");
        Files.writeString(script, "print('ok')", StandardCharsets.UTF_8);
        Files.writeString(agentDir.resolve("other.py"), "print('other')", StandardCharsets.UTF_8);

        sandbox = new WhitelistSandbox(
                List.of(workspace.toString()),
                List.of("echo"),
                List.of("127.0.0.1"),
                List.of("python"),
                true);
    }

    private SandboxAction shell(String command) {
        return new SandboxAction(ActionType.SHELL_EXEC, command, agentDir);
    }

    private void assertAllowed(String command) {
        assertThatCode(() -> sandbox.enforce(shell(command))).doesNotThrowAnyException();
    }

    private void assertRejected(String command, String expectFragment) {
        assertThatThrownBy(() -> sandbox.enforce(shell(command)))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining(expectFragment);
    }

    @Test
    void 允许执行本目录scripts下的脚本() {
        assertAllowed("python " + script);
        assertAllowed("python \"" + script + "\"");
    }

    @Test
    void 拒绝跨目录脚本() throws IOException {
        Path outside = workspace.resolve("outside").resolve("evil.py");
        Files.createDirectories(outside.getParent());
        Files.writeString(outside, "print(1)");

        assertRejected("python " + outside, "只允许执行本 Agent 目录下的脚本");
        assertRejected("python ../outside/evil.py", "只允许执行本 Agent 目录下的脚本");
    }

    @Test
    void 拒绝Agent目录内但不在scripts下的脚本() {
        assertRejected("python " + agentDir.resolve("other.py"), "只允许执行本 Agent 目录下的脚本");
    }

    @Test
    void 拒绝内联代码与模块形态() {
        assertRejected("python -c \"print(1)\"", "不允许内联代码");
        assertRejected("python -m http.server", "不允许内联代码");
    }

    @Test
    void 拒绝shell元字符串联() {
        assertRejected("python " + script + " && echo hacked", "元字符");
        assertRejected("python " + script + " | more", "元字符");
        assertRejected("python " + script + " > out.txt", "元字符");
    }

    @Test
    void 拒绝非白名单解释器() {
        assertRejected("ruby " + script, "解释器不在白名单内");
    }

    @Test
    void 拒绝不存在的脚本() {
        assertRejected("python " + agentDir.resolve("scripts").resolve("nope.py"), "只允许执行本 Agent 目录下的脚本");
    }

    @Test
    void legacy命令仍走旧白名单() {
        assertThatCode(() -> sandbox.enforce(
                new SandboxAction(ActionType.SHELL_EXEC, "echo hi"))).doesNotThrowAnyException();

        assertThatThrownBy(() -> sandbox.enforce(
                new SandboxAction(ActionType.SHELL_EXEC, "rm -rf /")))
                .isInstanceOf(SandboxViolationException.class);
    }

    @Test
    void 文件访问收窄到Agent目录且相对路径按agentDir解析() throws IOException {
        Files.createDirectories(agentDir.resolve("skills"));
        Files.writeString(agentDir.resolve("skills").resolve("report-format.md"), "格式");

        assertThatCode(() -> sandbox.enforce(
                new SandboxAction(ActionType.FILE_ACCESS, "skills/report-format.md", agentDir)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> sandbox.enforce(
                new SandboxAction(ActionType.FILE_ACCESS, "../other/secret.txt", agentDir)))
                .isInstanceOf(SandboxViolationException.class)
                .hasMessageContaining("不在该 Agent 目录内");
    }
}
