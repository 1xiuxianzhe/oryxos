package io.oryxos.core.session;

import java.util.List;
import java.util.Optional;

/**
 * 会话持久化端口。
 *
 * <p>core 只声明契约，具体实现（JPA + SQLite）由 {@code oryxos-storage} 提供，
 * 以保持存储依赖 core、core 不依赖存储的分层不变。</p>
 */
public interface SessionStore {

    /** 保存或更新完整会话；持久化失败必须向调用方传播。 */
    void save(Session session);

    /** 按会话标识读回；不存在返回空。 */
    Optional<Session> findById(String sessionId);

    /** 按渠道、用户和 Profile 查找活动会话；不存在返回空。 */
    Optional<Session> findByIdentity(String channel, String userId, String profileName);

    /** 返回可供轻量查询使用的会话摘要来源，不包含额外敏感数据。 */
    List<Session> list();

    /** 保持旧适配器和调用方的兼容性。 */
    default Optional<Session> find(String sessionId) {
        return findById(sessionId);
    }
}
