import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { ErrorBox, Loading, Stats, Tag } from '../ui.jsx';

/**
 * SandBox 策略：只给开关与条数。
 *
 * 当前没有认证，把路径 / 命令 / 域名清单吐到页面上等于把安全边界画给所有人看；
 * 而"开没开、配了几条"已经能回答运维最关心的问题。
 */
export default function Sandbox() {
  const [state, setState] = useState({ loading: true, error: null, sandbox: null });

  useEffect(() => {
    let cancelled = false;
    api
      .info()
      .then((info) =>
        !cancelled &&
        setState({
          loading: false,
          error: null,
          sandbox: info?.sandbox || { enabled: false, allowedPaths: 0, allowedCommands: 0, allowedDomains: 0 },
        }),
      )
      .catch((error) => !cancelled && setState({ loading: false, error, sandbox: null }));
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.loading) return <Loading />;
  if (state.error) return <ErrorBox error={state.error} />;

  const { sandbox } = state;

  return (
    <div>
      <p className="hint-top">
        核心阶段的沙箱是应用层白名单（路径 / 命令首 token / 出站域名），不是容器级隔离；完整沙箱在扩展阶段。
      </p>

      <div className="card">
        <div className="sec-head">
          <h3 className="sec">当前策略</h3>
          <Tag kind={sandbox.enabled ? 'ok' : 'warn'}>{sandbox.enabled ? '校验已启用' : '校验已关闭'}</Tag>
        </div>

        <Stats
          items={[
            { value: sandbox.allowedPaths, label: '路径', hint: '文件类工具允许的目录条数' },
            { value: sandbox.allowedCommands, label: '命令', hint: 'shell 首 token 白名单条数' },
            { value: sandbox.allowedDomains, label: '域名', hint: '出站 HTTP 域名条数' },
            { value: '只读', label: '管理方式', hint: '改 application.yaml 后重启' },
          ]}
        />

        <p className="hint">
          {sandbox.enabled
            ? '这里只展示策略规模，不展示具体路径 / 命令 / 域名：白名单内容属于安全边界信息。'
            : '注意：白名单校验已关闭，文件、命令与 HTTP 调用一律放行。该状态仅适用于本地开发。'}
        </p>
      </div>
    </div>
  );
}
