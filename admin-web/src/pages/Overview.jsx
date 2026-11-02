import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { ErrorBox, Loading, SectionTitle, Stats, Tag } from '../ui.jsx';

const CAPABILITIES = [
  ['对接 LLM', '显式 Provider 映射，多家协议统一'],
  ['ReAct 循环', '自实现推理–行动循环，完全可控'],
  ['Memory', '跨对话长期记忆，成长可积累'],
  ['Plugin Tool', '内置 Tool + MCP，强制沙箱白名单'],
  ['Web Service', 'REST API + 管理台对外门面'],
];

const TECH_STACK = ['Java 21', 'Spring Boot 3.x', 'Spring AI Alibaba', 'SQLite', 'Picocli'];

/** 只列关键端点：全量 schema 在 /swagger-ui.html。 */
const ENDPOINTS = [
  ['GET', '/api/v1/agents', 'Agent 列表（含加载失败目录）'],
  ['POST', '/api/v1/agents', '新建 Agent（脚手架 + 立即上线）'],
  ['PUT', '/api/v1/agents/{name}', '覆盖 AGENT.md（校验 + 重注册）'],
  ['DELETE', '/api/v1/agents/{name}', '下线并归档目录'],
  ['POST', '/api/v1/agents/{name}/generate-files', '按描述生成草稿（不落盘）'],
  ['POST', '/api/v1/agents/{name}/files', '保存文件并生效'],
  ['GET', '/api/v1/agents/{name}/session', 'Agent 专属会话'],
  ['GET', '/api/v1/workspace/tree', '工作区目录树'],
  ['GET', '/api/v1/workspace/file?path=', '读文件'],
  ['POST', '/api/v1/workspace/file', '写文件'],
  ['GET', '/api/v1/schedules', '定时任务（cron / 下次触发 / 最近运行）'],
  ['GET', '/api/v1/sessions', '会话列表'],
  ['GET', '/api/v1/sessions/{id}/runs', '会话运行明细（审计表）'],
  ['GET', '/api/v1/profiles', 'Profile 清单'],
  ['GET', '/api/v1/tools', '工具清单'],
  ['GET', '/api/v1/memory', '长期记忆快照'],
  ['GET', '/api/v1/health', '健康检查'],
  ['GET', '/api/v1/info', '系统信息'],
];

export default function Overview() {
  const [state, setState] = useState({ loading: true, error: null, data: null });

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [info, tools, sessions, agents, schedules] = await Promise.all([
          api.info(),
          api.tools(),
          api.sessions(),
          api.agents(),
          api.schedules(),
        ]);
        if (!cancelled) {
          setState({
            loading: false,
            error: null,
            data: {
              info: info || {},
              tools: tools || [],
              sessions: sessions || [],
              agents: agents?.agents || [],
              skippedAgents: agents?.skipped || [],
              schedules: schedules || { scheduler: {}, tasks: [], skipped: [] },
            },
          });
        }
      } catch (error) {
        if (!cancelled) setState({ loading: false, error, data: null });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (state.loading) return <Loading />;
  if (state.error) return <ErrorBox error={state.error} />;

  const { info, tools, sessions, agents, skippedAgents, schedules } = state.data;
  const providers = info.providers || [];
  const activeSessions = sessions.filter((s) => String(s.status || '').toLowerCase() !== 'archived').length;
  const sandbox = info.sandbox || { enabled: false, allowedPaths: 0, allowedCommands: 0, allowedDomains: 0 };

  return (
    <div>
      <div className="hero">
        <div className="hero-top">
          <h2 className="hero-title">{info.name || 'OryxOS'}</h2>
          <span className="badge">
            <span className="pulse" />
            运行中
          </span>
          <span className="ver mono">v{info.version || '0.1.0'}</span>
        </div>
        <p className="hero-sub">
          {info.description ||
            '装在你自己基础设施上的 Agent OS —— 统一底座运行多个业务 Agent，共享渠道、模型路由、工具、记忆与沙箱。'}
        </p>
      </div>

      <Stats
        items={[
          { value: agents.length, label: 'Agent', hint: 'agents/ 目录 + legacy YAML' },
          { value: tools.length, label: '内置 Tool', hint: '文件 / Shell / HTTP / 记忆' },
          { value: activeSessions, label: '活跃会话', hint: '未归档会话数' },
          { value: providers.length, label: 'Provider', hint: providers.map((p) => p.name).join('、') || '未配置' },
        ]}
      />

      <SectionTitle>五大核心能力</SectionTitle>
      <div className="caps">
        {CAPABILITIES.map(([name, desc], index) => (
          <div className="cap" key={name}>
            <span className="cap-idx mono">{index + 1}</span>
            <div>
              <div className="cap-name">{name}</div>
              <div className="cap-desc">{desc}</div>
            </div>
          </div>
        ))}
      </div>

      <SectionTitle>技术栈</SectionTitle>
      <div className="chips">
        {TECH_STACK.map((item, index) => (
          <span className={`chip ${index === 0 ? 'accent' : ''}`} key={item}>
            {item}
          </span>
        ))}
      </div>

      <SectionTitle>运行状态</SectionTitle>
      <div className="card">
        <div className="meta-row">
          <span>
            可调用模型 <Tag kind={info.canBuildAgents ? 'ok' : 'warn'}>{info.canBuildAgents ? '是' : '否'}</Tag>
          </span>
          <span>
            沙箱 <Tag kind={sandbox.enabled ? 'ok' : 'warn'}>{sandbox.enabled ? '已启用' : '已关闭'}</Tag>
          </span>
          <span>定时任务 {schedules.scheduler?.registered ?? 0} 条</span>
          <span>正在运行 {schedules.scheduler?.running ?? 0} 条</span>
        </div>
        <div className="meta-row">
          <span>Provider：</span>
          {providers.length === 0 && <span className="empty">（未配置）</span>}
          {providers.map((provider) => (
            <Tag key={provider.name} kind={provider.status === 'configured' ? 'brand' : 'warn'}>
              {provider.name}
            </Tag>
          ))}
        </div>

        {!info.canBuildAgents && (
          <p className="hint">当前没有可用 Provider：Agent 调用会返回 503，请先在 application.yaml 配置 oryxos.providers。</p>
        )}
        {skippedAgents.length > 0 && (
          <p className="hint">
            有 {skippedAgents.length} 个 Agent 目录加载失败（缺 AGENT.md、frontmatter 非法或 provider 未声明），
            详情见「Agent 列表」页底部。
          </p>
        )}
        {agents.some((agent) => !agent.fromDirectory) && (
          <p className="hint">存在 legacy YAML Agent：它们来自 .oryxos/profiles/，改配置仍需重启。</p>
        )}

        <details className="trace">
          <summary>端点清单（{ENDPOINTS.length}）</summary>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>方法</th>
                  <th>路径</th>
                  <th>说明</th>
                </tr>
              </thead>
              <tbody>
                {ENDPOINTS.map(([method, path, desc]) => (
                  <tr key={`${method}${path}`}>
                    <td className="mono">{method}</td>
                    <td className="mono">{path}</td>
                    <td>{desc}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="hint">完整接口文档见 /swagger-ui.html。</p>
        </details>
      </div>
    </div>
  );
}
