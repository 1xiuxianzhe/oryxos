-- 迁移脚本：请求级审计（agent_runs 表）+ 明细表补 run_id
--
-- 适用场景：**已经存在**的库（例如你本地已经跑过的 .oryxos/oryxos.db）。
--   schema.sql 里的 CREATE TABLE IF NOT EXISTS 对已存在的表是空操作，不会补列，
--   所以老库升级必须执行本脚本。
--
-- 执行方式（二选一）：
--   1) 什么都不做：application.yaml 里 ddl-auto=update，Hibernate 启动时会自动补上
--      llm_calls.run_id 与 tool_invocations.run_id 两列（agent_runs 新表仍建议手工建，见下）。
--   2) 手工执行：
--        sqlite3 .oryxos/oryxos.db < db/migration/2026-09-18-agent-runs-and-run-id.sql
--
-- 重复执行行为：CREATE TABLE / CREATE INDEX 带 IF NOT EXISTS，可重复执行；
--   但 SQLite 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，第二遍会报
--   "duplicate column name: run_id" —— 属预期，说明列已存在，跳过即可。
--
-- 铁律提醒：索引只允许建立在"该表创建时即存在"的列上，因此本脚本里
--   llm_calls / tool_invocations 的 run_id 索引放在 ALTER 之后（老库先补列再建索引）。

-- ============================================================
-- 1. 请求级审计表
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
-- 2. 明细表补 run_id（把每次 LLM / 工具调用挂到对应请求上）
-- ============================================================
ALTER TABLE llm_calls ADD COLUMN run_id TEXT;
ALTER TABLE tool_invocations ADD COLUMN run_id TEXT;

CREATE INDEX IF NOT EXISTS idx_llm_calls_run_id ON llm_calls (run_id);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_run_id ON tool_invocations (run_id);
