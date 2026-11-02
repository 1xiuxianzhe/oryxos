import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { DataTable, Empty, ErrorBox, Loading, Notice, SectionTitle, Tag } from '../ui.jsx';

/**
 * Agent 列表：新建即上线、删除即归档。
 *
 * 数据源是 GET /api/v1/agents（注册表 + 运行期跳过清单）——注册表才是"当前真能跑"的集合，
 * 磁盘上多出来的坏目录会落在 skipped 里，而不是伪装成可用 Agent。
 */
export default function AgentList() {
  const [state, setState] = useState({ loading: true, error: null, agents: [], skipped: [] });
  const [form, setForm] = useState({ open: false, name: '', description: '' });
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);

  async function load() {
    setState((prev) => ({ ...prev, loading: true, error: null }));
    try {
      const data = await api.agents();
      setState({ loading: false, error: null, agents: data?.agents || [], skipped: data?.skipped || [] });
    } catch (error) {
      setState({ loading: false, error, agents: [], skipped: [] });
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function onCreate(event) {
    event.preventDefault();
    setBusy(true);
    setNotice(null);
    try {
      await api.createAgent(form.name.trim(), form.description.trim());
      setForm({ open: false, name: '', description: '' });
      setNotice({ kind: 'ok', text: '已创建并立即上线（写目录 + 注册，无需重启）' });
      await load();
    } catch (error) {
      setNotice({ kind: 'err', text: error.message });
    } finally {
      setBusy(false);
    }
  }

  async function onDelete(name) {
    const confirmed = window.confirm(
      `删除「${name}」？\n整个目录会归档到 archive/，不物理删除，审计数据保持可追溯。`,
    );
    if (!confirmed) return;
    setNotice(null);
    try {
      const result = await api.deleteAgent(name);
      setNotice({ kind: 'ok', text: `已下线，目录归档到 ${result?.archivedTo || '（该 Agent 无目录）'}` });
      await load();
    } catch (error) {
      setNotice({ kind: 'err', text: error.message });
    }
  }

  const rows = state.agents.map((agent) => [
    <a className="mono" href={`#/agents/${encodeURIComponent(agent.name)}`}>
      {agent.name}
    </a>,
    agent.description || '—',
    agent.provider ? `${agent.provider} / ${agent.model || '默认'}` : '—',
    agent.scheduleCount > 0 ? `${agent.scheduleCount} 条` : '—',
    agent.fromDirectory ? <Tag kind="brand">目录</Tag> : <Tag>YAML</Tag>,
    <span className="ops" style={{ marginTop: 0 }}>
      <a className="btn" href={`#/agents/${encodeURIComponent(agent.name)}`}>
        详情
      </a>
      <button className="btn danger" onClick={() => onDelete(agent.name)}>
        删除
      </button>
    </span>,
  ]);

  return (
    <div>
      <p className="hint-top">
        Agent = 一个目录（.oryxos/agents/&lt;name&gt;/）。改 AGENT.md 或改目录结构都会在几百毫秒内生效，
        不需要重启；删除是"下线 + 归档"，不是抹掉。
      </p>

      <div className="card">
        <div className="sec-head">
          <h3 className="sec">新建 Agent</h3>
          <span className="hint">
            {form.open ? '名字只允许小写字母、数字与中划线' : '创建后自动生成 AGENT.md + scripts/ + skills/ + REFERENCE.md'}
          </span>
        </div>

        {!form.open && (
          <button className="btn primary" onClick={() => setForm((prev) => ({ ...prev, open: true }))}>
            + 新建 Agent
          </button>
        )}

        {form.open && (
          <form onSubmit={onCreate}>
            <div className="row">
              <input
                className="input mono"
                placeholder="agent-name"
                value={form.name}
                onChange={(event) => setForm((prev) => ({ ...prev, name: event.target.value }))}
                autoFocus
              />
              <input
                className="input"
                placeholder="一句话说明它负责什么（写进 AGENT.md 的角色段）"
                value={form.description}
                onChange={(event) => setForm((prev) => ({ ...prev, description: event.target.value }))}
              />
            </div>
            <div className="ops">
              <button className="btn primary" type="submit" disabled={busy || !form.name.trim()}>
                {busy ? '创建中…' : '创建并上线'}
              </button>
              <button
                className="btn"
                type="button"
                onClick={() => setForm({ open: false, name: '', description: '' })}
              >
                取消
              </button>
            </div>
          </form>
        )}

        {notice && <Notice kind={notice.kind}>{notice.text}</Notice>}
      </div>

      <SectionTitle
        extra={
          <button className="btn" onClick={load} disabled={state.loading}>
            刷新
          </button>
        }
      >
        Agent（{state.agents.length}）
      </SectionTitle>

      {state.loading && <Loading />}
      <ErrorBox error={state.error} />

      {!state.loading && !state.error && (
        <div className="card">
          <DataTable
            headers={['name', '说明', 'provider / model', '定时', '来源', '操作']}
            rows={rows}
            empty={
              <span>
                暂无 Agent。点上面「新建 Agent」，或往 .oryxos/agents/ 丢一个目录（<span className="mono">AGENT.md</span>
                是唯一入口文件）。
              </span>
            }
          />
        </div>
      )}

      {state.skipped.length > 0 && (
        <>
          <SectionTitle>加载失败的目录（{state.skipped.length}）</SectionTitle>
          <div className="card">
            <DataTable
              headers={['目录', '原因']}
              rows={state.skipped.map((item) => [item.dir, item.reason || '—'])}
            />
            <p className="hint">它们没有生效。改完文件会自动重试；不需要重启，也不会顶掉已注册的同名 Agent。</p>
          </div>
        </>
      )}

      {state.agents.length === 0 && state.skipped.length === 0 && !state.loading && !state.error && (
        <Empty>工作区是空的：新建一个 Agent 试试，或直接把目录拷进 .oryxos/agents/。</Empty>
      )}
    </div>
  );
}
