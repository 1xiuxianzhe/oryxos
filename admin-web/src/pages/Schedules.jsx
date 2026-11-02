import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { DataTable, ErrorBox, Loading, SectionTitle, Stats, Tag } from '../ui.jsx';
import { formatDuration, formatTime, formatTokens } from '../format.js';

/**
 * 定时任务（只读）：注册态来自内存调度器，运行史来自 SQLite 审计表。
 *
 * 口径必须说清楚：运行史按 Agent 聚合，同一 Agent 的多个任务共用一条 scheduler 会话，
 * 因此"最近运行"是这段会话的最近几次请求，不是"这个任务"的。宁可说明白，也不编造。
 */
export default function Schedules() {
  const [state, setState] = useState({ loading: true, error: null, data: null });
  const [selected, setSelected] = useState(null);

  useEffect(() => {
    let cancelled = false;
    api
      .schedules()
      .then((data) => !cancelled && setState({ loading: false, error: null, data }))
      .catch((error) => !cancelled && setState({ loading: false, error, data: null }));
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.loading) return <Loading />;
  if (state.error) return <ErrorBox error={state.error} />;

  const { scheduler = {}, tasks = [], skipped = [] } = state.data || {};
  const totalRuns = tasks.reduce((sum, task) => sum + (task.runCount || 0), 0);

  const rows = tasks.map((task) => [
    task.running ? <Tag kind="ok">运行中 · {formatDuration(task.runningForMs)}</Tag> : <Tag>已注册</Tag>,
    task.profileName,
    task.id,
    <code>{task.cron}</code>,
    task.timezone || '系统时区',
    task.nextRunAt ? formatTime(task.nextRunAt) : '—',
    task.lastRun ? (
      <span>
        <Tag kind={task.lastRun.success ? 'ok' : 'warn'}>{task.lastRun.stopReason || (task.lastRun.success ? '成功' : '失败')}</Tag>
        {formatTime(task.lastRun.startedAt)}
      </span>
    ) : (
      '尚未运行'
    ),
    task.runCount,
    task.sessionId ? task.sessionId.slice(0, 8) : '—',
  ]);

  return (
    <div>
      <p className="hint-top">
        cron 与注册状态来自运行中的调度器（改 schedules 需要重启）；运行史来自 agent_runs，按 Agent 聚合。
      </p>

      <Stats
        items={[
          { value: scheduler.registered ?? 0, label: '已注册任务', hint: 'Profile 里声明的 schedules' },
          { value: scheduler.running ?? 0, label: '正在运行', hint: '此刻占着防重叠锁' },
          { value: totalRuns, label: '累计触发', hint: 'agent_runs 计数（含失败）' },
          { value: scheduler.poolSize ?? 0, label: '调度线程', hint: 'oryxos.scheduler.pool-size' },
        ]}
      />

      <SectionTitle extra={<span className="hint">点任意一行看最近运行明细</span>}>任务（{tasks.length}）</SectionTitle>
      <div className="card">
        <DataTable
          headers={['状态', 'Agent', '任务 ID', 'cron', '时区', '下次触发', '最近运行', '累计', '会话']}
          rows={rows}
          selectedIndex={selected}
          onRowClick={(index) => setSelected(index === selected ? null : index)}
          empty="还没有任何定时任务。在 AGENT.md 的 schedules 段里加一条，保存后立即生效。"
        />

        {selected !== null && tasks[selected] && <TaskDetail task={tasks[selected]} />}
      </div>

      {skipped.length > 0 && (
        <>
          <SectionTitle>未生效的配置（{skipped.length}）</SectionTitle>
          <div className="card">
            <DataTable
              headers={['Agent', '任务 ID', 'cron', '原因']}
              rows={skipped.map((item) => [item.profileName, item.id || '—', item.cron || '—', item.reason])}
            />
            <p className="hint">这些条目在注册时被跳过，不会触发；原因与启动日志里的一致。</p>
          </div>
        </>
      )}
    </div>
  );
}

function TaskDetail({ task }) {
  return (
    <div className="run-card" style={{ marginTop: 14 }}>
      <div className="run-head">
        <span className="mono">{`${task.profileName} / ${task.id}`}</span>
        <Tag kind="brand">{task.cron}</Tag>
        <span>{task.timezone || '系统时区'}</span>
        <span>下次触发 {task.nextRunAt ? formatTime(task.nextRunAt) : '—'}</span>
        {task.sessionId && <span className="mono">会话 {task.sessionId}</span>}
      </div>

      <div className="run-question">{task.message || '（未配置消息）'}</div>

      {task.recentRuns?.length ? (
        <>
          <div className="trace-title">最近 {task.recentRuns.length} 次运行</div>
          <DataTable
            headers={['结束方式', '结果', '轮数', '工具', 'Token', '耗时', '开始时间']}
            rows={task.recentRuns.map((run) => [
              run.stopReason || run.status || '—',
              <Tag kind={run.success ? 'ok' : 'warn'}>{run.success ? '成功' : '失败'}</Tag>,
              run.iterations,
              run.toolCallCount,
              formatTokens(run.totalTokens),
              formatDuration(run.durationMs),
              formatTime(run.startedAt),
            ])}
          />
          {task.recentRuns.find((run) => run.errorMessage) && (
            <div className="error-box">
              <span className="code">
                {task.recentRuns.find((run) => run.errorMessage).stopReason || 'ERROR'}
              </span>
              <span className="msg">{task.recentRuns.find((run) => run.errorMessage).errorMessage}</span>
            </div>
          )}
        </>
      ) : (
        <div className="empty">这段会话还没有定时触发的记录。</div>
      )}
    </div>
  );
}
