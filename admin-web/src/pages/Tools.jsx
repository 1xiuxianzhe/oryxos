import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { DataTable, ErrorBox, Loading } from '../ui.jsx';

/** Tool 清单：来自工具注册表（内置 + MCP），不是目录列举。 */
export default function Tools() {
  const [state, setState] = useState({ loading: true, error: null, tools: [] });

  useEffect(() => {
    let cancelled = false;
    api
      .tools()
      .then((tools) => !cancelled && setState({ loading: false, error: null, tools: tools || [] }))
      .catch((error) => !cancelled && setState({ loading: false, error, tools: [] }));
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.loading) return <Loading />;
  if (state.error) return <ErrorBox error={state.error} />;

  return (
    <div>
      <p className="hint-top">
        工具是 Agent 唯一能干实事的手。Agent 的 tools 段限制"能看见哪些"，沙箱白名单限制"能碰哪些"。
      </p>
      <div className="card">
        <DataTable
          headers={['名称', '描述']}
          rows={state.tools.map((tool) => [tool.name, tool.description || '—'])}
          empty="工具注册表为空。"
        />
        <p className="hint">清单来自工具注册表（内置工具 + MCP），不是文件系统列举。</p>
      </div>
    </div>
  );
}
