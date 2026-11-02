-- 迁移脚本：为「既有库」的 tool_invocations 表补上审计所需列。
--
-- 适用场景：数据库建立于本特性之前（tool_invocations 表已存在，但没有 success / error_message / latency_ms）。
-- 全新库无需执行：db/schema.sql 建表时已包含这些列。
--
-- 背景：SQLite 不支持 "ALTER TABLE ... ADD COLUMN IF NOT EXISTS"，
-- 且 CREATE TABLE IF NOT EXISTS 对已存在的表不会补列，因此需要手工执行一次。
-- 若某列已存在，重复执行会报 "duplicate column name"，忽略即可。
--
-- 执行方式（示例）：
--   sqlite3 <workspace>/.oryxos/oryxos.db < 2026-09-16-tool-invocations-audit-columns.sql

ALTER TABLE tool_invocations ADD COLUMN success INTEGER;
ALTER TABLE tool_invocations ADD COLUMN error_message TEXT;
ALTER TABLE tool_invocations ADD COLUMN latency_ms INTEGER;

CREATE INDEX IF NOT EXISTS idx_tool_invocations_session_id ON tool_invocations (session_id);
CREATE INDEX IF NOT EXISTS idx_tool_invocations_tool_name ON tool_invocations (tool_name);

-- 注意：
--   1. 旧表可能带有历史遗留的 error 列，本迁移不改名、不删除，保持历史数据可读；
--      审计逻辑一律写 error_message。
--   2. 若既有库中的 sessions / tool_invocations 是由 Hibernate ddl-auto=update 建出的
--      （时间列为 TEXT 亲和性），读回时间字段会报 "Error parsing time stamp"；
--      开发库建议直接删除 .oryxos/oryxos.db 让脚本重建。
