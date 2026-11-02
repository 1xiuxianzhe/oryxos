package io.oryxos.storage.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionStore;
import io.oryxos.storage.entity.SessionEntity;
import io.oryxos.storage.repository.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JPA + SQLite 的 SessionStore 适配器。 */
@Component
public class JpaSessionStore implements SessionStore {

    private final SessionRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JpaSessionStore(SessionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("session 不能为空");
        }
        SessionEntity entity = repository.findById(session.getSessionId())
                .orElseGet(SessionEntity::new);
        entity.setSessionId(session.getSessionId());
        entity.setProfileName(session.getProfileName());
        entity.setChannel(session.getChannel());
        entity.setUserId(session.getUserId());
        entity.setMessagesJson(serialize(session.getMessages()));
        entity.setStatus(session.getStatus());
        entity.setCreatedAt(session.getCreatedAt());
        entity.setLastActiveAt(session.getLastActiveAt());
        entity.setArchivedAt(session.getArchivedAt());
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Session> findById(String sessionId) {
        return repository.findById(sessionId).map(this::toSession);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Session> findByIdentity(String channel, String userId, String profileName) {
        return repository.findFirstByChannelAndUserIdAndProfileNameAndStatus(
                        channel, userId, profileName, "active")
                .map(this::toSession);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Session> list() {
        return repository.findAllByOrderByLastActiveAtDesc().stream()
                .map(this::toSession)
                .toList();
    }

    private String serialize(List<Session.Message> messages) {
        List<Map<String, String>> raw = new ArrayList<>(messages.size());
        for (Session.Message message : messages) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.getRole() == null ? "" : message.getRole());
            item.put("content", message.getContent() == null ? "" : message.getContent());
            raw.add(item);
        }
        try {
            return objectMapper.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("会话历史序列化失败: " + e.getMessage(), e);
        }
    }

    private Session toSession(SessionEntity entity) {
        Session session = new Session(entity.getSessionId(), entity.getProfileName(),
                entity.getChannel(), entity.getUserId());
        session.restore(deserialize(entity.getMessagesJson()), entity.getStatus(),
                entity.getCreatedAt(), entity.getLastActiveAt(), entity.getArchivedAt());
        return session;
    }

    private List<Session.Message> deserialize(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> raw = objectMapper.readValue(json, new TypeReference<>() {
            });
            List<Session.Message> messages = new ArrayList<>(raw.size());
            for (Map<String, String> item : raw) {
                messages.add(new Session.Message(
                        item.getOrDefault("role", "user"),
                        item.getOrDefault("content", "")));
            }
            return messages;
        } catch (Exception e) {
            throw new IllegalStateException("会话历史反序列化失败: " + e.getMessage(), e);
        }
    }
}
