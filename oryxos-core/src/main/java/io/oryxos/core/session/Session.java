package io.oryxos.core.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Session —— 用户和 Agent 一次对话的上下文容器。
 * 核心阶段内存版，第四周落 SQLite（由 oryxos-storage 的 SessionEntity 承接）。
 */
public class Session {

    private final String sessionId;
    private final String profileName;
    private final String channel;
    private final String userId;
    private final List<Message> messages = new ArrayList<>();
    private String status = "active";
    private Instant createdAt = Instant.now();
    private Instant lastActiveAt = Instant.now();
    private Instant archivedAt;

    public Session(String sessionId, String profileName, String channel, String userId) {
        this.sessionId = sessionId;
        this.profileName = profileName;
        this.channel = channel;
        this.userId = userId;
    }

    public void addMessage(Message message) {
        this.messages.add(message);
        this.lastActiveAt = Instant.now();
    }

    public void archive() {
        this.status = "archived";
        this.archivedAt = Instant.now();
    }

    /**
     * 从持久化状态恢复（由存储适配器调用），使重启后能接续同一会话。
     * 仅用于恢复；正常运行期间历史只允许通过 {@link #addMessage(Message)} 追加。
     */
    public void restore(List<Message> restoredMessages,
                        String restoredStatus,
                        Instant restoredCreatedAt,
                        Instant restoredLastActiveAt,
                        Instant restoredArchivedAt) {
        this.messages.clear();
        if (restoredMessages != null) {
            this.messages.addAll(restoredMessages);
        }
        if (restoredStatus != null) {
            this.status = restoredStatus;
        }
        if (restoredCreatedAt != null) {
            this.createdAt = restoredCreatedAt;
        }
        if (restoredLastActiveAt != null) {
            this.lastActiveAt = restoredLastActiveAt;
        }
        this.archivedAt = restoredArchivedAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProfileName() {
        return profileName;
    }

    public String getChannel() {
        return channel;
    }

    public String getUserId() {
        return userId;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActiveAt() {
        return lastActiveAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    /** 单条消息。 */
    public static class Message {
        private final String role;
        private final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }
}
