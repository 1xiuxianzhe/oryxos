import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { ErrorBox, Loading } from '../ui.jsx';

/** 全局长期记忆快照：注入视角，不是磁盘原文（归档区会被截断）。 */
export default function Memory() {
  const [state, setState] = useState({ loading: true, error: null, memory: null });

  useEffect(() => {
    let cancelled = false;
    api
      .memory()
      .then((memory) => !cancelled && setState({ loading: false, error: null, memory }))
      .catch((error) => !cancelled && setState({ loading: false, error, memory: null }));
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.loading) return <Loading />;
  if (state.error) return <ErrorBox error={state.error} />;

  const memory = state.memory || { content: '', chars: 0, note: '' };

  return (
    <div>
      <p className="hint-top">
        全局记忆（&lt;workspace&gt;/memory/MEMORY.md）。每个 Agent 自己的记忆在「Agent 详情 → 记忆」里看。
      </p>
      <div className="card">
        <div className="meta-row">
          <span>字符数 {memory.chars}</span>
          <span>{memory.note || '只读'}</span>
        </div>
        {memory.content ? (
          <pre className="pre">{memory.content}</pre>
        ) : (
          <div className="empty">暂无长期记忆。让 Agent 调用 save_memory 之后，这里会出现内容。</div>
        )}
        <p className="hint">来源：经 MemoryService 门面读取，与注入到 prompt 的内容完全一致。</p>
      </div>
    </div>
  );
}
