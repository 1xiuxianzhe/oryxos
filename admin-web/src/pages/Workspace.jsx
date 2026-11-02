import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { ErrorBox, Loading, Notice } from '../ui.jsx';

/**
 * 工作区：整棵 .oryxos/ 的树 + 文件读写。
 *
 * 写 agents/&lt;name&gt;/AGENT.md 时后端走"校验 + 重注册"，所以这里不需要提示"记得重启"；
 * 写其它文件（scripts/、REFERENCE.md）是直接原子落盘——它们是 Agent 的资源，不是配置。
 */
export default function Workspace() {
  const [tree, setTree] = useState({ loading: true, error: null, data: null });
  const [selected, setSelected] = useState(null);
  const [content, setContent] = useState('');
  const [fileError, setFileError] = useState(null);
  const [notice, setNotice] = useState(null);
  const [busy, setBusy] = useState(false);

  async function loadTree() {
    try {
      const data = await api.tree();
      setTree({ loading: false, error: null, data });
    } catch (error) {
      setTree({ loading: false, error, data: null });
    }
  }

  useEffect(() => {
    loadTree();
  }, []);

  async function open(node) {
    setSelected(node);
    setFileError(null);
    setNotice(null);
    try {
      const file = await api.readFile(node.path);
      setContent(file?.content ?? '');
    } catch (error) {
      setContent('');
      setFileError(error);
    }
  }

  async function save() {
    if (!selected) return;
    setBusy(true);
    setNotice(null);
    try {
      await api.writeFile(selected.path, content);
      setNotice({
        kind: 'ok',
        text: selected.path.endsWith('/AGENT.md') ? '已保存并重新注册（定义变更立即生效）' : '已保存',
      });
      await loadTree();
    } catch (error) {
      setNotice({ kind: 'err', text: error.message });
    } finally {
      setBusy(false);
    }
  }

  if (tree.loading) return <Loading />;
  if (tree.error) return <ErrorBox error={tree.error} />;

  const nodes = tree.data?.nodes || [];

  return (
    <div>
      <p className="hint-top">
        路径相对 .oryxos/（深度 {tree.data?.maxDepth ?? 3} / 单目录 {tree.data?.maxEntries ?? 200} 条）。
        绝对路径与 .. 会被后端拒绝；符号链接指向工作区外同样拒绝。
      </p>

      <div className="card">
        <div className="ws">
          <div className="tree">
            {nodes.length === 0 && <div className="empty">工作区为空。</div>}
            {nodes.map((node) => (
              <TreeNode key={node.path} node={node} onOpen={open} selected={selected} />
            ))}
          </div>

          <div className="ws-file">
            {!selected && <div className="empty">选左侧一个文件查看 / 编辑</div>}
            {selected && (
              <>
                <div className="file-path mono">{selected.path}</div>
                {notice && <Notice kind={notice.kind}>{notice.text}</Notice>}
                <ErrorBox error={fileError} />
                <textarea
                  className="textarea code"
                  value={content}
                  onChange={(event) => setContent(event.target.value)}
                  disabled={Boolean(fileError)}
                />
                <div className="ops">
                  <button className="btn primary" onClick={save} disabled={busy || Boolean(fileError)}>
                    {busy ? '保存中…' : '保存'}
                  </button>
                  <span className="hint">
                    {selected.path.endsWith('/AGENT.md')
                      ? '这是 Agent 定义：保存会先校验再重注册'
                      : '普通文件：直接原子写入'}
                  </span>
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function TreeNode({ node, onOpen, selected }) {
  const [open, setOpen] = useState(node.name === 'agents');

  if (node.type === 'dir') {
    return (
      <div className="tree-node">
        <button className="tree-dir" onClick={() => setOpen((value) => !value)}>
          <span className="caret">{open ? '▾' : '▸'}</span>
          {node.name}
        </button>
        {open && (
          <div className="tree-children">
            {(node.children || []).length === 0 && <div className="empty">（空）</div>}
            {(node.children || []).map((child) => (
              <TreeNode key={child.path} node={child} onOpen={onOpen} selected={selected} />
            ))}
          </div>
        )}
      </div>
    );
  }

  return (
    <button
      className={`tree-file ${selected?.path === node.path ? 'active' : ''}`}
      onClick={() => onOpen(node)}
      title={`${node.size} 字节`}
    >
      {node.name}
    </button>
  );
}
