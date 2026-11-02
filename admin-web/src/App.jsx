import { useEffect, useState } from 'react';
import { api } from './api.js';
import Overview from './pages/Overview.jsx';
import AgentList from './pages/AgentList.jsx';
import AgentDetail from './pages/AgentDetail.jsx';
import Schedules from './pages/Schedules.jsx';
import Sessions from './pages/Sessions.jsx';
import Providers from './pages/Providers.jsx';
import Tools from './pages/Tools.jsx';
import Sandbox from './pages/Sandbox.jsx';
import Memory from './pages/Memory.jsx';
import Workspace from './pages/Workspace.jsx';

/**
 * 管理台骨架：左侧分组导航 + 右侧内容区。
 *
 * 分组口径（31 节）：顶层只放业务向的三项（概览 / Agent / 定时任务），
 * 「OS 运行时」收纳底座自身的状态（会话、Provider、Tool、SandBox、记忆、工作区）——
 * 前者回答"我的 Agent 怎么样"，后者回答"底座本身怎么样"，混在一列里会让人分不清主次。
 */
const TOP_NAV = [
  { key: 'overview', label: '概览' },
  { key: 'agents', label: 'Agent 列表' },
  { key: 'schedules', label: '定时任务' },
];

const RUNTIME_NAV = [
  { key: 'sessions', label: '会话列表' },
  { key: 'providers', label: 'Provider 列表' },
  { key: 'tools', label: 'Tool 列表' },
  { key: 'sandbox', label: 'SandBox 列表' },
  { key: 'memory', label: '长期记忆' },
  { key: 'workspace', label: '工作区' },
];

const RUNTIME_KEYS = new Set(RUNTIME_NAV.map((item) => item.key));

const PAGES = {
  overview: Overview,
  agents: AgentList,
  schedules: Schedules,
  sessions: Sessions,
  providers: Providers,
  tools: Tools,
  sandbox: Sandbox,
  memory: Memory,
  workspace: Workspace,
};

const PAGE_TITLES = Object.fromEntries([...TOP_NAV, ...RUNTIME_NAV].map((item) => [item.key, item.label]));

/** hash 路由：#/agents/demo → { page:'agents', param:'demo' }。 */
function parseRoute() {
  const raw = window.location.hash.replace(/^#\/?/, '');
  const parts = raw.split('/').filter(Boolean);
  const page = PAGES[parts[0]] ? parts[0] : 'overview';
  const param = parts.length > 1 ? decodeURIComponent(parts.slice(1).join('/')) : null;
  return { page, param };
}

export default function App() {
  const [route, setRoute] = useState(parseRoute());
  const [runtimeOpen, setRuntimeOpen] = useState(RUNTIME_KEYS.has(parseRoute().page));
  const [health, setHealth] = useState({ online: true, version: '' });

  useEffect(() => {
    const onHashChange = () => {
      const next = parseRoute();
      setRoute(next);
      if (RUNTIME_KEYS.has(next.page)) setRuntimeOpen(true);
    };
    window.addEventListener('hashchange', onHashChange);
    if (!window.location.hash) window.history.replaceState(null, '', '#/overview');
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const check = async () => {
      try {
        const info = await api.info();
        if (!cancelled) setHealth({ online: true, version: info?.version || '' });
      } catch {
        if (!cancelled) setHealth((prev) => ({ ...prev, online: false }));
      }
    };
    check();
    const timer = window.setInterval(check, 30000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  const goto = (key) => {
    window.location.hash = `#/${key}`;
  };

  const Page = PAGES[route.page];
  const isAgentDetail = route.page === 'agents' && Boolean(route.param);

  return (
    <div className="layout">
      <aside className="sidebar">
        <a className="brand" href="#/overview">
          <span className="brand-mark">OryxOS</span>
          <span className="brand-sub">Agent OS</span>
        </a>

        <nav className="nav">
          {TOP_NAV.map((item) => (
            <button
              key={item.key}
              className={`nav-item ${route.page === item.key ? 'on' : ''}`}
              onClick={() => goto(item.key)}
            >
              {item.label}
            </button>
          ))}

          <button
            className={`nav-item nav-group ${runtimeOpen ? 'open' : ''}`}
            onClick={() => setRuntimeOpen((open) => !open)}
          >
            OS 运行时
            <span className="chevron">{runtimeOpen ? '▾' : '▸'}</span>
          </button>

          {runtimeOpen &&
            RUNTIME_NAV.map((item) => (
              <button
                key={item.key}
                className={`nav-item nav-sub ${route.page === item.key ? 'on' : ''}`}
                onClick={() => goto(item.key)}
              >
                {item.label}
              </button>
            ))}
        </nav>

        <div className="sidebar-foot">
          <span className={`dot ${health.online ? 'ok' : 'bad'}`} />
          <span className="foot-text">{health.online ? '运行中' : '不可达'}</span>
          <span className="foot-ver mono">{health.version ? `v${health.version}` : ''}</span>
        </div>
      </aside>

      <main className="content">
        {/* 概览页自带 hero 大标题，再顶一个 h2 就是重复；其余页面统一用 page-head */}
        {route.page !== 'overview' && (
          <div className="page-head">
            <h2>{isAgentDetail ? 'Agent 详情' : PAGE_TITLES[route.page]}</h2>
            <span className="page-sub mono">{isAgentDetail ? route.param : ''}</span>
          </div>
        )}

        <div key={`${route.page}/${route.param || ''}`}>
          {isAgentDetail ? (
            <AgentDetail name={route.param} />
          ) : (
            <Page />
          )}
        </div>
      </main>
    </div>
  );
}
