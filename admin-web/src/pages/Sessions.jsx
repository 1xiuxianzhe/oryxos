import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { DataTable, ErrorBox, Loading, SectionTitle, Stats, Tag } from '../ui.jsx';
import { formatDuration, formatTime, formatTokens, roleLabel, statusKind } from '../format.js';

/**
 * 会话列表 + 详情（消息 + 运行明细）。
 *
 * 两个端点刻意分开取：消息读内存会话（{@code /sessions/{id}}），明细读 SQLite 审计表
 * （{@code /sessions/{id}/runs}）。聚合代价不同，前端并行发、分别渲染。
 */
export default function Sessions() {
  const [state, setState] = useState({ loading: true, error: null, sessions: [] });
  const [selected, setSelected] = useState(null);
  const [detail, setDetail] = useState(null);

  useEffect(() => {
    let cancelled = false;
    api
      .sessions()
      .then((sessions) => !cancelled && setState({ loading: false, error: null, sessions: sessions || [] }))
      .catch((error) => !cancelled && setState({ loading: false, error, sessions: [] }));
    return () => {
      cancelled = true;
    };
  }, []);

  async function open(sessionId) {
    setSelected(sessionId);
    setDetail({ loading: true });
    try {
      const [session, audit] = await Promise.all([api.session(sessionId), api.sessionRuns(sessionId)]);
      setDetail({ loading: false, error: null, session, audit });
    } catch (error) {
      setDetail({ loading: false, error });
    }
  }

  if (state.loading) return <Loading />;
  if (state.error) return <ErrorBox error={state.error} />;

  const rows = state.sessions.map((session) => [
    <button
      className="btn"
      style={{ marginTop: 0 }}
      onClick={() => open(session.sessionId)}
    >
      {session.sessionId}
    </button>,
    session.profileName,
    session.channel,
    session.userId,
    <Tag kind={statusKind(session.status)}>{session.status}</Tag>,
    session.messageCount,
    formatTime(session.lastActiveAt),
  ]);

  return (
    <div>
      <p className="hint-top">
        点「查看」展开最近 100 条消息，以及每次提问调了哪些工具、花了多少 token（运行明细来自 SQLite 审计表）。
      </p>

      {detail && (
        <div className="card">
          <button
            className="btn back"
            onClick={() => {
              setDetail(null);
              setSelected(null);
            }}
          >
            ← 收起详情
          </button>
          {detail.loading && <Loading />}
          <ErrorBox error={detail.error} />
          {detail.session && <SessionDetail session={detail.session} audit={detail.audit} />}
        </div>
      )}

      <SectionTitle>会话（{state.sessions.length}）</SectionTitle>
      <div className="card">
        <DataTable
          headers={['会话 ID', 'Agent', '渠道', '用户', '状态', '消息', '最近活跃']}
          rows={rows}
          empty="暂无会话。通过 CLI 聊天、调用 POST /api/v1/sessions，或在 Agent 详情页发消息之后，这里会出现记录。"
        />
        {selected && !detail && <p className="hint">正在打开 {selected}…</p>}
      </div>
    </div>
  );
}

function SessionDetail({ session, audit }) {
  return (
    <>
      <div className="meta-row">
        <span className="mono">{session.session.sessionId}</span>
        <Tag kind="brand">{session.session.profileName}</Tag>
        <span>{session.session.channel} / {session.session.userId}</span>
        <span>最多 {session.messageLimit} 条</span>
        <span>创建 {formatTime(session.session.createdAt)}</span>
      </div>

      <SectionTitle>对话内容</SectionTitle>
      {session.messages.length === 0 && <div className="empty">这个会话还没有消息。</div>}
      <div className="chat">
        {session.messages.map((message, index) => (
          <div key={index} className={`msg ${message.role}`}>
            <div className="msg-role">{roleLabel(message.role)}</div>
            <pre className="msg-body">{message.content || '（空）'}</pre>
          </div>
        ))}
      </div>

      {audit && <Audit audit={audit} />}
    </>
  );
}

function Audit({ audit }) {
  const totals = audit.totals || {
    runs: 0,
    llmCalls: 0,
    toolCalls: 0,
    promptTokens: 0,
    completionTokens: 0,
    totalTokens: 0,
    durationMs: 0,
  };
  const runs = audit.runs || [];

  const attachedLlm = runs.reduce((sum, run) => sum + (run.llmCalls?.length || 0), 0);
  const attachedTools = runs.reduce((sum, run) => sum + (run.toolCalls?.length || 0), 0);
  const orphanLlm = totals.llmCalls - attachedLlm;
  const orphanTools = totals.toolCalls - attachedTools;

  return (
    <div style={{ marginTop: 22 }}>
      <SectionTitle extra={<span className="hint mono">来自 SQLite 审计表</span>}>运行明细</SectionTitle>

      <Stats
        items={[
          { value: totals.runs, label: '提问次数', hint: 'agent_runs 一行一次' },
          { value: totals.llmCalls, label: '模型调用', hint: 'llm_calls（含失败）' },
          { value: totals.toolCalls, label: '工具调用', hint: 'tool_invocations' },
          { value: formatTokens(totals.totalTokens), label: '总 Token', hint: '输入 + 输出' },
        ]}
      />

      <div className="meta-row">
        <span>输入 {formatTokens(totals.promptTokens)} · 输出 {formatTokens(totals.completionTokens)}</span>
        <span>累计耗时 {formatDuration(totals.durationMs)}</span>
        <span>按 run_id 关联：一次提问 → 它的模型调用与工具调用</span>
      </div>

      {(orphanLlm > 0 || orphanTools > 0) && (
        <p className="hint">
          另有 {orphanLlm} 次模型调用、{orphanTools} 次工具调用没有 run_id 关联（早期记录或直接调用 Provider），
          已计入上面的汇总，但不会挂到具体某次提问下。
        </p>
      )}

      {runs.length === 0 && (
        <div className="empty">这个会话还没有请求记录。审计从每次处理开始写入，聊过一轮之后这里就有账。</div>
      )}

      <div className="run-list">
        {runs.map((run, index) => (
          <RunCard key={run.runId || index} run={run} index={runs.length - index} />
        ))}
      </div>
    </div>
  );
}

function RunCard({ run, index }) {
  return (
    <div className="run-card">
      <div className="run-head">
        <span className="run-index">#{index}</span>
        <span>{formatTime(run.startedAt)}</span>
        <Tag kind={run.success ? 'ok' : 'warn'}>{run.stopReason || run.status || 'FAILED'}</Tag>
        <Tag>{run.model || '未指定模型'}</Tag>
        <span>
          {formatDuration(run.durationMs)} · {run.iterations || 0} 轮 · {run.toolCallCount || 0} 次工具 ·{' '}
          {formatTokens(run.totalTokens)} tokens
        </span>
      </div>

      <div className="run-question">{run.userMessage || '（未记录提问内容）'}</div>

      {run.errorMessage && (
        <div className="error-box">
          <span className="code">{run.stopReason || 'ERROR'}</span>
          <span className="msg">{run.errorMessage}</span>
        </div>
      )}

      {run.toolCalls?.length ? (
        <>
          <div className="trace-title">工具调用</div>
          <DataTable
            headers={['工具', '参数', '结果', '耗时', '状态']}
            rows={run.toolCalls.map((call) => [
              call.toolName,
              <span className="clip">{call.parameters || '—'}</span>,
              <span className="clip">
                {call.result || '—'}
                {call.resultTruncated && <span className="clip-mark">（已截断）</span>}
              </span>,
              formatDuration(call.latencyMs),
              <Tag kind={call.success ? 'ok' : 'warn'}>{call.success ? '成功' : '失败'}</Tag>,
            ])}
          />
        </>
      ) : (
        <p className="hint">这次提问没有调用工具——模型直接给出了答复。</p>
      )}

      {run.llmCalls?.length ? (
        <details className="trace">
          <summary>
            模型调用明细（{run.llmCalls.length} 次，含重试与失败的尝试；token 逐次记在这里）
          </summary>
          <DataTable
            headers={['#', '模型', '输入', '输出', '总计', '耗时', '状态']}
            rows={run.llmCalls.map((call, callIndex) => [
              `#${callIndex + 1}`,
              call.model || '—',
              formatTokens(call.promptTokens),
              formatTokens(call.completionTokens),
              formatTokens(call.totalTokens),
              formatDuration(call.latencyMs),
              <Tag kind={call.success ? 'ok' : 'warn'}>{call.success ? '成功' : '失败'}</Tag>,
            ])}
          />
        </details>
      ) : null}
    </div>
  );
}
