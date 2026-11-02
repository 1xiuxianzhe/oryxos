package io.oryxos.storage.repository;

import io.oryxos.storage.entity.AgentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * agent_runs 仓储 —— 请求级审计的查询入口。
 *
 * <p>后台展示用得最多的两种查询：按会话看某次对话的全部请求、按时间看最近的请求。</p>
 */
public interface AgentRunRepository extends JpaRepository<AgentRunEntity, String> {

    /** 某会话下的全部请求，按开始时间倒序（最近的在最前）。 */
    List<AgentRunEntity> findBySessionIdOrderByStartedAtDesc(String sessionId);

    /** 全局最近的请求（后台列表页）。 */
    List<AgentRunEntity> findTop50ByOrderByStartedAtDesc();

    /**
     * 某个渠道 + Agent 的最近若干次请求（定时任务页用）。
     *
     * <p>按 Profile 聚合而不是按任务：同一 Profile 的所有定时任务共用一条
     * {@code ("scheduler","scheduler",profile)} 会话，任务级的"第几次触发"在
     * {@code agent_runs} 里没有独立字段，硬拆只会得到假数据。</p>
     */
    List<AgentRunEntity> findTop10ByChannelAndProfileNameOrderByStartedAtDesc(String channel, String profileName);

    /** 某个渠道 + Agent 的累计请求数。 */
    long countByChannelAndProfileName(String channel, String profileName);
}
