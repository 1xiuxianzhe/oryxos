-- 迁移脚本：为「既有库」的 llm_calls 表补上审计所需的 success / error_message 列。
--
-- 适用场景：数据库是在本次变更之前建立的（llm_calls 表已存在但没有这两列）。
-- 全新库无需执行：db/schema.sql 建表时已包含这两列。
--
-- 背景：SQLite 不支持 "ALTER TABLE ... ADD COLUMN IF NOT EXISTS"，
-- 且 CREATE TABLE IF NOT EXISTS 对已存在的表不会补列，因此需要手工执行一次。
-- 若这两列已存在，重复执行会报 "duplicate column name"，忽略即可。
--
-- 执行方式（示例）：
--   sqlite3 <workspace>/.oryxos/oryxos.db < 2026-09-15-llm-calls-add-success-and-error-message.sql

ALTER TABLE llm_calls ADD COLUMN success INTEGER;
ALTER TABLE llm_calls ADD COLUMN error_message TEXT;

CREATE INDEX IF NOT EXISTS idx_llm_calls_success ON llm_calls (success);
