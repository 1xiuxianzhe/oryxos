/** 展示层的格式化工具：只做"把后端字段变成人话"，不含业务判断。 */

const pad = (value) => (value < 10 ? `0${value}` : String(value));

export function formatTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  );
}

export function formatTokens(value) {
  if (value === null || value === undefined || value === '' || Number.isNaN(Number(value))) return '0';
  return Number(value).toLocaleString('zh-CN');
}

export function formatDuration(ms) {
  if (ms === null || ms === undefined || ms === '' || Number.isNaN(Number(ms))) return '—';
  const value = Number(ms);
  if (value < 1000) return `${value} ms`;
  if (value < 60000) return `${(value / 1000).toFixed(1)} s`;
  return `${Math.floor(value / 60000)} 分 ${Math.round((value % 60000) / 1000)} 秒`;
}

export function roleLabel(role) {
  return { user: '用户', assistant: '助手', tool: '工具', system: '系统' }[role] ?? role;
}

/** 会话状态 → Tag 色调。 */
export function statusKind(status) {
  const value = String(status || '').toLowerCase();
  if (value === 'active') return 'ok';
  if (value === 'archived') return 'mute';
  return 'mute';
}

/** 数值型字段缺省显示为 —，避免出现 "null" 字样。 */
export function orDash(value) {
  return value === null || value === undefined || value === '' ? '—' : value;
}
