-- Contract: 审计表与会话表的手工建表脚本（SQLite）
-- Feature: 002-react-loop
--
-- 适用规则（沿用能力一踩坑后的约定）：
--   1. 本脚本只用于「全新库」首次建表；既有库的列演进见 db/migration/ 下的脚本。
--   2. CREATE TABLE IF NOT EXISTS 对已存在的旧表是空操作，不会补列。
--   3. 索引只允许建立在「建表时即存在」的列上，否则旧库会因缺列而启动失败。
--      因此本特性为 llm_calls / tool_invocations 新增的 run_id 列**不进**本脚本的索引，
--      索引放在迁移脚本里（老库先补列、再建索引）。
--   4. 时间列使用整数亲和性（epoch 毫秒）；TEXT 亲和性会把时间戳转成字符串导致读回解析失败。
--
-- 权威实现：oryxos-storage/src/main/resources/db/schema.sql（本文件与之保持一致）。

-- ============================================================
-- tool_invocations：每次工具调用记录（成功/失败都落库，day one 审计地基）
-- 口径与 llm_calls 对齐：status（SUCCESS/FAILED）+ success + error_message + latency_ms
-- run_id：所属请求级审计（agent_runs.run_id），使"一次提问 → 它调用过的工具"可下钻（FR-023）
-- ============================================================
CREATE TABLE IF NOT EXISTS tool_invocations (
    invocation_id TEXT PRIMARY KEY,
    session_id    TEXT,
    run_id        TEXT,
    profile_name  TEXT,
    tool_name     TEXT,
    parameters    TEXT,
    status        TEXT,
    success       INTEGER,
    error_message TEXT,
    result        TEXT,
    latency_ms    INTEGER,
    started_at    INTEGER,
    completed_at  INTEGER
);

CREATE INDEX IF NOT EXISTS idx_tool_invocations_session_id ON tool_invocations (session_id);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_tool_name ON tool_invocations (tool_name);

-- ============================================================
-- sessions：会话元数据 + JSON 序列化的对话历史
-- ============================================================
CREATE TABLE IF NOT EXISTS sessions (
    session_id     TEXT PRIMARY KEY,
    profile_name   TEXT,
    channel        TEXT,
    user_id        TEXT,
    messages_json  TEXT,
    status         TEXT,
    created_at     INTEGER,
    last_active_at INTEGER,
    archived_at    INTEGER
);

CREATE INDEX IF NOT EXISTS idx_sessions_profile_name ON sessions (profile_name);

-- ============================================================
-- agent_runs：请求级审计 —— 一次处理（一次用户提问）一条记录（FR-022）
-- 两阶段写：处理开始 INSERT(RUNNING) → 处理结束 UPDATE(FINISHED)
-- 后台据此展示：问了什么 / 用的哪个模型 / 花了多少 token / 成没成、为何没成
-- ============================================================
CREATE TABLE IF NOT EXISTS agent_runs (
    run_id            TEXT PRIMARY KEY,
    session_id        TEXT,
    profile_name      TEXT,
    channel           TEXT,
    user_id           TEXT,
    user_message      TEXT,
    provider          TEXT,
    model             TEXT,
    status            TEXT,
    stop_reason       TEXT,
    success           INTEGER,
    iterations        INTEGER,
    tool_call_count   INTEGER,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    error_message     TEXT,
    started_at        INTEGER,
    completed_at      INTEGER
);

CREATE INDEX IF NOT EXISTS idx_agent_runs_session_id ON agent_runs (session_id);
CREATE INDEX IF NOT EXISTS idx_agent_runs_profile_name ON agent_runs (profile_name);
CREATE INDEX IF NOT EXISTS idx_agent_runs_started_at ON agent_runs (started_at);

-- ============================================================
-- 既有库迁移脚本（db/migration/ 下；仅在「库建立于本次变更之前」时手工执行一次）
-- ============================================================
-- 2026-09-16-tool-invocations-audit-columns.sql
--   ALTER TABLE tool_invocations ADD COLUMN success INTEGER;
--   ALTER TABLE tool_invocations ADD COLUMN error_message TEXT;
--   ALTER TABLE tool_invocations ADD COLUMN latency_ms INTEGER;
--
-- 2026-09-18-agent-runs-and-run-id.sql
--   CREATE TABLE IF NOT EXISTS agent_runs (...);            -- 见上
--   ALTER TABLE llm_calls        ADD COLUMN run_id TEXT;    -- 明细关联请求
--   ALTER TABLE tool_invocations ADD COLUMN run_id TEXT;
--   CREATE INDEX IF NOT EXISTS idx_llm_calls_run_id        ON llm_calls (run_id);
--   CREATE INDEX IF NOT EXISTS idx_tool_invocations_run_id ON tool_invocations (run_id);
--
-- 两个脚本里的 ALTER TABLE 都不支持 IF NOT EXISTS：重复执行会报 duplicate column name，
-- 属预期（说明列已存在），跳过即可。
--
-- 注意：既有开发库中若 sessions / tool_invocations / llm_calls 是由 Hibernate ddl-auto=update
-- 建出的（时间列为 TEXT 亲和性），建议直接删除 .oryxos/oryxos.db 让脚本重建，避免时间列读回失败。
