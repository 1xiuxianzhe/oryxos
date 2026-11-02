package io.oryxos.core.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionManagerTest {

    @Test
    void 相同业务身份复用同一会话() {
        SessionManager manager = new SessionManager();

        Session first = manager.getOrCreate("cli", "local", "default");
        Session second = manager.getOrCreate("cli", "local", "default");

        assertThat(second).isSameAs(first);
        assertThat(second.getSessionId()).isEqualTo(first.getSessionId());
    }

    @Test
    void 业务身份任一字段变化都会隔离会话() {
        SessionManager manager = new SessionManager();

        Session base = manager.getOrCreate("cli", "local", "default");

        assertThat(manager.getOrCreate("web", "local", "default").getSessionId())
                .isNotEqualTo(base.getSessionId());
        assertThat(manager.getOrCreate("cli", "other", "default").getSessionId())
                .isNotEqualTo(base.getSessionId());
        assertThat(manager.getOrCreate("cli", "local", "demo").getSessionId())
                .isNotEqualTo(base.getSessionId());
    }

    @Test
    void 有持久化端口时新上下文按业务身份恢复() {
        InMemoryStore store = new InMemoryStore();
        SessionManager firstManager = new SessionManager(store);
        Session first = firstManager.getOrCreate("cli", "local", "default");
        first.addMessage(new Session.Message("user", "hello"));
        firstManager.save(first);

        Session restored = new SessionManager(store).getOrCreate("cli", "local", "default");

        assertThat(restored.getSessionId()).isEqualTo(first.getSessionId());
        assertThat(restored.getMessages()).extracting(Session.Message::getContent)
                .containsExactly("hello");
    }

    private static final class InMemoryStore implements SessionStore {
        private Session session;

        @Override
        public void save(Session session) {
            this.session = session;
        }

        @Override
        public java.util.Optional<Session> findById(String sessionId) {
            return session != null && session.getSessionId().equals(sessionId)
                    ? java.util.Optional.of(session) : java.util.Optional.empty();
        }

        @Override
        public java.util.Optional<Session> findByIdentity(String channel, String userId, String profileName) {
            return session != null && session.getChannel().equals(channel)
                    && session.getUserId().equals(userId)
                    && session.getProfileName().equals(profileName)
                    ? java.util.Optional.of(session) : java.util.Optional.empty();
        }

        @Override
        public java.util.List<Session> list() {
            return session == null ? java.util.List.of() : java.util.List.of(session);
        }
    }
}
