import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { ErrorBox, KV, Loading, Notice, Tabs } from '../ui.jsx';
import { roleLabel } from '../format.js';

const TABS = [
  { key: 'info', label: '基本信息' },
  { key: 'generate', label: '生成' },
  { key: 'files', label: '文件' },
  { key: 'session', label: '会话' },
  { key: 'memory', label: '记忆' },
];

/**
 * Agent 详情：看配置 → 生成草稿 → 改文件 → 试聊 → 看记忆。
 *
 * 五件事都落在同一批端点上（/api/v1/agents/{name}/… 与 /api/v1/workspace/…），
 * 区别只是"改的是定义"还是"改的是资源"：AGENT.md 走后端校验 + 重注册，脚本与资料直接原子写。
 */
export default function AgentDetail({ name }) {
  const [detail, setDetail] = useState(null);
  const [error, setError] = useState(null);
  const [tab, setTab] = useState('info');

  async function load() {
    try {
      setDetail(await api.agent(name));
      setError(null);
    } catch (err) {
      setError(err);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name]);

  return (
    <div>
      <a className="btn back" href="#/agents">
        ← 返回 Agent 列表
      </a>

      <ErrorBox error={error} />
      {!detail && !error && <Loading />}

      {detail && (
        <>
          <Tabs tabs={TABS} active={tab} onChange={setTab} />
          {tab === 'info' && <InfoTab detail={detail} />}
          {tab === 'generate' && <GenerateTab name={name} onSaved={load} />}
          {tab === 'files' && <FilesTab name={name} files={detail.files || []} onSaved={load} />}
          {tab === 'session' && <SessionTab name={name} />}
          {tab === 'memory' && <MemoryTab name={name} />}
        </>
      )}
    </div>
  );
}

function InfoTab({ detail }) {
  return (
    <div className="card">
      <KV
        items={[
          { label: 'name', value: detail.name, mono: true },
          { label: 'description', value: detail.description },
          { label: 'provider', value: detail.provider },
          { label: 'model', value: detail.model },
          {
            label: 'tools',
            value: detail.tools?.length ? detail.tools.join('、') : '未限制（模型能看到全部工具）',
          },
          { label: '定时', value: detail.schedules?.length ? detail.schedules.join('；') : '无' },
          { label: '目录', value: detail.dir || '（legacy YAML，无目录）', mono: true },
          { label: '文件', value: detail.files?.length ? `${detail.files.length} 个` : '—' },
        ]}
      />
      {detail.dir && <p className="hint">目录即定义：改这个目录里的文件（或在「文件」tab 编辑）就会改这个 Agent。</p>}
      {!detail.dir && (
        <p className="hint">
          这个 Agent 来自 legacy YAML（.oryxos/profiles/）。它没有目录，因此不能在本页编辑——迁移到 agents/ 后可管。
        </p>
      )}
    </div>
  );
}

function GenerateTab({ name, onSaved }) {
  const [description, setDescription] = useState('');
  const [draft, setDraft] = useState(null);
  const [note, setNote] = useState(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState(null);

  async function onGenerate() {
    setBusy(true);
    setNotice(null);
    try {
      const result = await api.generateFiles(name, description.trim());
      setDraft(result?.files || {});
      setNote(result?.note || null);
    } catch (error) {
      setDraft(null);
      setNotice({ kind: 'err', text: error.message });
    } finally {
      setBusy(false);
    }
  }

  async function onSave() {
    setBusy(true);
    setNotice(null);
    try {
      const result = await api.saveFiles(name, draft);
      setNotice({ kind: 'ok', text: `已保存并生效：${(result?.written || []).join('、')}` });
      await onSaved();
    } catch (error) {
      setNotice({ kind: 'err', text: error.message });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card">
      <div className="sec-head">
        <h3 className="sec">按描述生成</h3>
        <span className="hint">生成只是草稿，保存后才生效</span>
      </div>

      <label className="field-label">描述这个 Agent 要做什么</label>
      <textarea
        className="textarea"
        rows={4}
        placeholder="例：每天早上抓取团队仓库的 PR，按模块分组汇总成中文摘要"
        value={description}
        onChange={(event) => setDescription(event.target.value)}
      />
      <div className="ops">
        <button className="btn primary" onClick={onGenerate} disabled={busy || !description.trim()}>
          {busy && !draft ? '生成中…' : '用大模型生成'}
        </button>
        {draft && (
          <button className="btn" onClick={onSave} disabled={busy}>
            {busy ? '保存中…' : '保存并生效'}
          </button>
        )}
      </div>

      {notice && <Notice kind={notice.kind}>{notice.text}</Notice>}
      {note && <Notice kind="info">{note}</Notice>}

      {draft &&
        Object.entries(draft).map(([path, content]) => (
          <div key={path} style={{ marginTop: 14 }}>
            <div className="file-path mono">{path}</div>
            <textarea
              className="textarea code"
              value={content}
              onChange={(event) => setDraft({ ...draft, [path]: event.target.value })}
              rows={path === 'AGENT.md' ? 18 : 8}
            />
          </div>
        ))}
    </div>
  );
}

function FilesTab({ name, files, onSaved }) {
  const [selected, setSelected] = useState(null);
  const [content, setContent] = useState('');
  const [notice, setNotice] = useState(null);
  const [busy, setBusy] = useState(false);

  async function open(path) {
    setSelected(path);
    setNotice(null);
    try {
      const file = await api.readFile(`agents/${name}/${path}`);
      setContent(file?.content ?? '');
    } catch (error) {
      setContent('');
      setNotice({ kind: 'err', text: error.message });
    }
  }

  async function save() {
    setBusy(true);
    setNotice(null);
    try {
      await api.writeFile(`agents/${name}/${selected}`, content);
      setNotice({
        kind: 'ok',
        text: selected === 'AGENT.md' ? '已保存并重新注册（定义变更立即生效）' : '已保存',
      });
      await onSaved();
    } catch (error) {
      setNotice({ kind: 'err', text: error.message });
    } finally {
      setBusy(false);
    }
  }

  if (files.length === 0) {
    return <div className="card"><div className="empty">这个 Agent 目录里没有可读文件。</div></div>;
  }

  return (
    <div className="card">
      <p className="hint-top">
        改 <span className="mono">AGENT.md</span> = 改定义（校验通过才写盘）；改 <span className="mono">scripts/</span> = 改行为
        （脚本以本机权限执行，注意信任边界）。
      </p>
      <div className="ws">
        <div className="ws-tree">
          {files.map((path) => (
            <div
              key={path}
              className={`ws-node ${selected === path ? 'on' : ''}`}
              onClick={() => open(path)}
            >
              <span className="mono">{path}</span>
            </div>
          ))}
        </div>
        <div className="ws-file">
          {!selected && <div className="empty">点左侧一个文件查看 / 编辑</div>}
          {selected && (
            <>
              <div className="file-path mono">{`agents/${name}/${selected}`}</div>
              {notice && <Notice kind={notice.kind}>{notice.text}</Notice>}
              <textarea
                className="textarea code"
                value={content}
                onChange={(event) => setContent(event.target.value)}
              />
              <div className="ops">
                <button className="btn primary" onClick={save} disabled={busy}>
                  {busy ? '保存中…' : '保存'}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function SessionTab({ name }) {
  const [state, setState] = useState({ loading: true, error: null, sessionId: null, messages: [] });
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);

  async function load() {
    try {
      const data = await api.agentSession(name);
      setState({
        loading: false,
        error: null,
        sessionId: data?.sessionId || null,
        messages: data?.messages || [],
      });
    } catch (error) {
      setState({ loading: false, error, sessionId: null, messages: [] });
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [name]);

  async function send() {
    const content = input.trim();
    if (!content) return;
    setSending(true);
    setInput('');
    setState((prev) => ({
      ...prev,
      messages: [...prev.messages, { role: 'user', content }],
      error: null,
    }));
    try {
      const reply = await api.sendAgentMessage(name, content);
      setState((prev) => ({
        ...prev,
        messages: [...prev.messages, { role: 'assistant', content: reply?.content || '' }],
      }));
    } catch (error) {
      setState((prev) => ({ ...prev, error }));
    } finally {
      setSending(false);
    }
  }

  return (
    <div className="card">
      <div className="sec-head">
        <h3 className="sec">管理台会话</h3>
        <span className="hint mono">{state.sessionId || '（会话尚未创建）'}</span>
      </div>

      {state.loading && <Loading />}
      <ErrorBox error={state.error} />

      {!state.loading && state.messages.length === 0 && (
        <div className="empty">还没有消息，在下面发一条试试。</div>
      )}

      <div className="chat">
        {state.messages.map((message, index) => (
          <div key={index} className={`msg ${message.role === 'user' ? 'user' : 'assistant'}`}>
            <div className="msg-role">{roleLabel(message.role)}</div>
            <pre className="msg-body">{message.content || '（空）'}</pre>
          </div>
        ))}
      </div>

      <div className="row">
        <input
          className="input"
          placeholder="给这个 Agent 发一条消息（与业务对话分开记录）"
          value={input}
          onChange={(event) => setInput(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') send();
          }}
        />
        <button className="btn primary" onClick={send} disabled={sending || !input.trim()}>
          {sending ? '思考中…' : '发送'}
        </button>
      </div>
      {sending && <p className="hint">ReAct 循环可能需要几秒（每一步都要调模型）。</p>}
    </div>
  );
}

function MemoryTab({ name }) {
  const [state, setState] = useState({ loading: true, error: null, memory: null });

  useEffect(() => {
    let cancelled = false;
    api
      .agentMemory(name)
      .then((memory) => !cancelled && setState({ loading: false, error: null, memory }))
      .catch((error) => !cancelled && setState({ loading: false, error, memory: null }));
    return () => {
      cancelled = true;
    };
  }, [name]);

  if (state.loading) return <Loading />;

  return (
    <div className="card">
      <div className="sec-head">
        <h3 className="sec">长期记忆（只读）</h3>
        <span className="hint">{state.memory?.note || ''}</span>
      </div>
      <ErrorBox error={state.error} />
      {state.memory && (
        <pre className="pre">{state.memory.content || '（这个 Agent 还没有记忆）'}</pre>
      )}
      <p className="hint">由 save_memory 工具写入 &lt;agentDir&gt;/memory/MEMORY.md，此处只读。</p>
    </div>
  );
}
