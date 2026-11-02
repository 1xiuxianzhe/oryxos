/**
 * 后端接口封装（/api/v1）。
 *
 * 本仓的响应**没有**统一信封：成功直接是 DTO（数组/对象），失败是 `ErrorBody{errorCode,message,timestamp}`
 * 加非 2xx 状态码。因此这里按 HTTP 状态判断成败，并把 errorCode 带到 Error 上——
 * 页面要能区分"配置缺失(503)"与"内容不合法(400)"，这两种错误的下一步操作完全不同。
 */
const BASE = '/api/v1';

const enc = (value) => encodeURIComponent(value);

async function request(path, options = {}) {
  const response = await fetch(`${BASE}${path}`, {
    headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
    ...options,
  });

  const text = await response.text();
  let body = null;
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = { message: text };
    }
  }

  if (!response.ok) {
    const error = new Error(body?.message || `请求失败（HTTP ${response.status}）`);
    error.code = body?.errorCode || `HTTP_${response.status}`;
    error.status = response.status;
    throw error;
  }
  return body;
}

const json = (method, payload) => ({ method, body: JSON.stringify(payload) });

export const api = {
  // ---- 系统 ----
  health: () => request('/health'),
  info: () => request('/info'),

  // ---- Agent（30 节：动态管理）----
  agents: () => request('/agents'),
  agent: (name) => request(`/agents/${enc(name)}`),
  createAgent: (name, description) => request('/agents', json('POST', { name, description })),
  deleteAgent: (name) => request(`/agents/${enc(name)}`, { method: 'DELETE' }),
  updateAgent: (name, markdown) => request(`/agents/${enc(name)}`, json('PUT', { markdown })),
  generateFiles: (name, description) =>
    request(`/agents/${enc(name)}/generate-files`, json('POST', { description })),
  saveFiles: (name, files) => request(`/agents/${enc(name)}/files`, json('POST', { files })),
  agentMemory: (name) => request(`/agents/${enc(name)}/memory`),
  agentSession: (name) => request(`/agents/${enc(name)}/session`),
  sendAgentMessage: (name, content) =>
    request(`/agents/${enc(name)}/session/messages`, json('POST', { content })),

  // ---- 只读目录 ----
  profiles: () => request('/profiles'),
  providers: () => request('/providers'),
  tools: () => request('/tools'),
  memory: () => request('/memory'),

  // ---- 会话与审计 ----
  sessions: () => request('/sessions'),
  session: (id) => request(`/sessions/${enc(id)}`),
  sessionRuns: (id) => request(`/sessions/${enc(id)}/runs`),

  // ---- 定时任务（只读）----
  schedules: () => request('/schedules'),

  // ---- 工作区 ----
  tree: () => request('/workspace/tree'),
  readFile: (path) => request(`/workspace/file?path=${enc(path)}`),
  writeFile: (path, content) => request('/workspace/file', json('POST', { path, content })),
};
