import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { DataTable, ErrorBox, Loading, Tag } from '../ui.jsx';

/** Provider 清单：只暴露名字、Base URL 与装配状态，不返回 api-key。 */
export default function Providers() {
  const [state, setState] = useState({ loading: true, error: null, providers: [] });

  useEffect(() => {
    let cancelled = false;
    api
      .providers()
      .then((providers) => !cancelled && setState({ loading: false, error: null, providers: providers || [] }))
      .catch((error) => !cancelled && setState({ loading: false, error, providers: [] }));
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.loading) return <Loading />;
  if (state.error) return <ErrorBox error={state.error} />;

  return (
    <div>
      <p className="hint-top">
        Provider 是"模型从哪来"。Agent 只写 provider 名字，不感知具体厂商；显式映射保证同名 Bean 不会串号。
      </p>
      <div className="card">
        <DataTable
          headers={['名称', 'Base URL', '状态']}
          rows={state.providers.map((provider) => [
            provider.name,
            provider.baseUrl || '—',
            <Tag kind={provider.status === 'configured' ? 'ok' : 'warn'}>
              {provider.status === 'configured' ? 'configured' : provider.status || '未知'}
            </Tag>,
          ])}
          empty="未配置任何 Provider。Agent 调用会返回 503，请在 application.yaml 的 oryxos.providers 里配置。"
        />
        <p className="hint">
          状态来自运行时装配结果：configured 表示已装配出模型客户端，not-assembled 表示配置里有但没生效。
        </p>
      </div>
    </div>
  );
}
