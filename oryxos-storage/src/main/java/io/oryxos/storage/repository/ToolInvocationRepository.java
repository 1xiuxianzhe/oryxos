package io.oryxos.storage.repository;

import io.oryxos.storage.entity.ToolInvocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * tool_invocations 表的基本 CRUD 与按会话查询。
 */
public interface ToolInvocationRepository extends JpaRepository<ToolInvocationEntity, String> {

    /** 按 sessionId 查询该会话的所有工具调用记录。 */
    List<ToolInvocationEntity> findBySessionId(String sessionId);

    /** 按 runId 查询某一次处理（一次用户提问）下的全部工具调用——与 agent_runs 关联。 */
    List<ToolInvocationEntity> findByRunId(String runId);
}
