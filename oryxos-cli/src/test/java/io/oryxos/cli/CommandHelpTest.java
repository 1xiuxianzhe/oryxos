package io.oryxos.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

import static org.assertj.core.api.Assertions.assertThat;

class CommandHelpTest {

    @Test
    void 根命令帮助包含核心命令() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CommandLine command = new CommandLine(new OryxOsCli());
        command.setOut(new PrintWriter(out, true));

        assertThat(command.execute("--help")).isZero();
        assertThat(out.toString()).contains("init", "status", "chat", "serve", "gateway",
                "profile", "provider", "tool", "session");
    }
}
