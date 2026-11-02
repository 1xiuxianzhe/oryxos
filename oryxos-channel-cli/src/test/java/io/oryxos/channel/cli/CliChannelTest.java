package io.oryxos.channel.cli;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CliChannelTest {
    @Test
    void 两轮输入复用同一会话并跳过空行() {
        SessionManager sessions = new SessionManager();
        AgentService agent = mock(AgentService.class);
        doAnswer(invocation -> {
            Session session = invocation.getArgument(0);
            String message = invocation.getArgument(1);
            session.addMessage(new Session.Message("user", message));
            session.addMessage(new Session.Message("assistant", "reply-" + (session.getMessages().size() / 2)));
            return session.getMessages().get(session.getMessages().size() - 1).getContent();
        }).when(agent).process(any(Session.class), any());
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new CliChannel(sessions, agent,
                new ByteArrayInputStream(" hello \n\nworld\n/quit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8)).start("demo");

        Session session = sessions.getOrCreate("cli", "local", "demo");
        verify(agent).process(eq(session), eq("hello"));
        verify(agent).process(eq(session), eq("world"));
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("reply-0", "reply-1")
                .doesNotContain("echo:");
        assertThat(session.getMessages()).hasSize(4);
    }

    @Test
    void AgentService失败时输出错误且不伪造回复() {
        AgentService agent = mock(AgentService.class);
        doThrow(new IllegalStateException("model unavailable"))
                .when(agent).process(any(Session.class), eq("hello"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new CliChannel(new SessionManager(), agent,
                new ByteArrayInputStream("hello\n/quit\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(output, true, StandardCharsets.UTF_8)).start("default");

        assertThat(output.toString(StandardCharsets.UTF_8)).contains("处理失败")
                .doesNotContain("echo:");
    }
}
