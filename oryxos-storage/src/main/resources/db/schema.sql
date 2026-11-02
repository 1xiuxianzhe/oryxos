-- OryxOS 手工建表脚本（SQLite）—— 仅用于「全新库」的首次建表
-- 说明：SQLite 上 hibernate.ddl-auto=update 对表结构演进支持很弱，
-- 因此审计/会话表结构由本脚本手工维护，不依赖 Hibernate 自动迁移。
--
-- 三条铁律（能力一踩坑后确立）：
--   1. CREATE TABLE IF NOT EXISTS 对「已存在的旧表」是空操作，不会补列；列演进请执行 db/migration/ 下的脚本。
--   2. 索引只允许建立在「建表时即存在」的列上，否则旧库会因缺列而报 "no such column"。
--   3. 时间列使用整数亲和性（epoch 毫秒）；TEXT 亲和性会把时间戳转成字符串，导致读回时解析失败。

-- ============================================================
-- llm_calls：每次 LLM 调用记录（成功/失败都落库）
-- ============================================================
CREATE TABLE IF NOT EXISTS llm_calls (
    call_id           TEXT PRIMARY KEY,
    session_id        TEXT,
    -- run_id：请求级审计关联（老库需执行 db/migration/2026-09-18-agent-runs-and-run-id.sql 补列）
    run_id            TEXT,
    provider          TEXT,
    model             TEXT,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    latency_ms        INTEGER,
    status            TEXT,
    success           INTEGER,
    error_message     TEXT,
    started_at        INTEGER,
    completed_at      INTEGER
);

CREATE INDEX IF NOT EXISTS idx_llm_calls_session_id ON llm_calls (session_id);
CREATE INDEX IF NOT EXISTS idx_llm_calls_provider ON llm_calls (provider);

-- ============================================================
-- tool_invocations：每次工具调用记录（成功/失败都落库，day one 审计地基）
-- 口径与 llm_calls 对齐：status(SUCCESS/FAILED) + success + error_message + latency_ms
-- ============================================================
CREATE TABLE IF NOT EXISTS tool_invocations (
    invocation_id TEXT PRIMARY KEY,
    session_id    TEXT,
    -- run_id：请求级审计关联（老库需执行 db/migration/2026-09-18-agent-runs-and-run-id.sql 补列）
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
CREATE INDEX IF NOT EXISTS idx_sessions_identity
    ON sessions (channel, user_id, profile_name, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sessions_active_identity
    ON sessions (channel, user_id, profile_name)
    WHERE status = 'active';

-- ============================================================
-- agent_runs：请求级审计 —— 一次处理（一次用户提问）一条记录
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
