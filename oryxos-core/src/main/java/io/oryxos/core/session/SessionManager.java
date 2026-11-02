package io.oryxos.core.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session 管理：内存索引优先，未命中时恢复持久化会话，最后创建新会话。
 */
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Object> identityLocks = new ConcurrentHashMap<>();
    private final SessionStore sessionStore;

    public SessionManager() {
        this(null);
    }

    public SessionManager(SessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    /**
     * 按业务身份幂等获取活动会话。
     */
    public Session getOrCreate(String channel, String userId, String profileName) {
        String identity = identityKey(channel, userId, profileName);
        Session cached = findCachedByIdentity(channel, userId, profileName);
        if (cached != null) {
            return cached;
        }
        Object lock = identityLocks.computeIfAbsent(identity, ignored -> new Object());
        synchronized (lock) {
            cached = findCachedByIdentity(channel, userId, profileName);
            if (cached != null) {
                return cached;
            }
            if (sessionStore != null) {
                Session persisted = sessionStore.findByIdentity(channel, userId, profileName)
                        .filter(session -> !"archived".equalsIgnoreCase(session.getStatus()))
                        .orElse(null);
                if (persisted != null) {
                    sessions.put(persisted.getSessionId(), persisted);
                    return persisted;
                }
            }
            Session created = create(UUID.randomUUID().toString(), profileName, channel, userId);
            logger.debug("创建会话: sessionId={}, profile={}, channel={}, userId={}",
                    created.getSessionId(), profileName, channel, userId);
            return created;
        }
    }

    /** 兼容旧调用方，参数顺序为 profile、channel、user。 */
    public Session create(String profileName, String channel, String userId) {
        return create(UUID.randomUUID().toString(), profileName, channel, userId);
    }

    public Session create(String sessionId, String profileName, String channel, String userId) {
        Session session = new Session(sessionId, profileName, channel, userId);
        sessions.put(sessionId, session);
        return session;
    }

    /** 先查内存；未命中且有存储时从库读回并放回内存。 */
    public Session get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Session session = sessions.get(sessionId);
        if (session != null) {
            return session;
        }
        if (sessionStore == null) {
            return null;
        }
        return sessionStore.findById(sessionId)
                .map(loaded -> {
                    sessions.put(loaded.getSessionId(), loaded);
                    return loaded;
                })
                .orElse(null);
    }

    /** 持久化会话；存储异常向调用方传播。 */
    public void save(Session session) {
        if (session == null) {
            return;
        }
        if (sessionStore != null) {
            sessionStore.save(session);
        }
        sessions.put(session.getSessionId(), session);
    }

    public void archive(String sessionId) {
        Session session = get(sessionId);
        if (session != null) {
            session.archive();
            save(session);
        }
    }

    public Map<String, Session> all() {
        return Map.copyOf(sessions);
    }

    public List<Session> list() {
        if (sessionStore == null) {
            return List.copyOf(sessions.values());
        }
        List<Session> persisted = new ArrayList<>(sessionStore.list());
        persisted.forEach(session -> sessions.put(session.getSessionId(), session));
        return List.copyOf(persisted);
    }

    public boolean exists(String sessionId) {
        return sessionId != null && (sessions.containsKey(sessionId)
                || (sessionStore != null && sessionStore.findById(sessionId).isPresent()));
    }

    private Session findCachedByIdentity(String channel, String userId, String profileName) {
        return sessions.values().stream()
                .filter(session -> session.getChannel().equals(channel)
                        && session.getUserId().equals(userId)
                        && session.getProfileName().equals(profileName)
                        && !"archived".equalsIgnoreCase(session.getStatus()))
                .findFirst()
                .orElse(null);
    }

    private static String identityKey(String channel, String userId, String profileName) {
        return String.join("\u0000", safe(channel), safe(userId), safe(profileName));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
