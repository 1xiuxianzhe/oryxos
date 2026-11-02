/** 通用展示件：只负责长相，不含数据请求。 */

export function Card({ children, className = '' }) {
  return <div className={`card ${className}`.trim()}>{children}</div>;
}

export function SectionTitle({ children, extra }) {
  return (
    <div className="sec-head">
      <h3 className="sec">{children}</h3>
      {extra}
    </div>
  );
}

export function Stats({ items }) {
  return (
    <div className="stats">
      {items.map((item) => (
        <div className="stat" key={item.label}>
          <div className="stat-value">{item.value}</div>
          <div className="stat-label">{item.label}</div>
          <div className="stat-hint">{item.hint}</div>
        </div>
      ))}
    </div>
  );
}

export function Tag({ children, kind = 'mute' }) {
  return <span className={`tag ${kind}`}>{children}</span>;
}

export function Notice({ kind = 'info', children }) {
  return <div className={`notice ${kind}`}>{children}</div>;
}

export function Empty({ children }) {
  return <div className="empty">{children}</div>;
}

export function Loading({ text = '加载中…' }) {
  return <div className="empty">{text}</div>;
}

export function ErrorBox({ error }) {
  if (!error) return null;
  return (
    <div className="error-box">
      <span className="code">{error.code || 'ERROR'}</span>
      <span className="msg">{error.message}</span>
    </div>
  );
}

/**
 * 表格：rows 是「单元格数组」，单元格可以是字符串或 ReactNode。
 * 用 th 的字符串做 key 会有重名风险，所以统一用下标。
 */
export function DataTable({ headers, rows, onRowClick, selectedIndex, empty }) {
  return (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            {headers.map((header, index) => (
              <th key={index}>{header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 && (
            <tr>
              <td className="empty" colSpan={headers.length}>
                {empty || '（暂无数据）'}
              </td>
            </tr>
          )}
          {rows.map((cells, rowIndex) => (
            <tr
              key={rowIndex}
              className={[
                onRowClick ? 'clickable' : '',
                selectedIndex === rowIndex ? 'selected' : '',
              ].join(' ').trim()}
              onClick={onRowClick ? () => onRowClick(rowIndex) : undefined}
            >
              {cells.map((cell, cellIndex) => (
                <td key={cellIndex} className={cellIndex === 0 ? 'mono' : undefined}>
                  {cell === null || cell === undefined || cell === '' ? '—' : cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function Tabs({ tabs, active, onChange }) {
  return (
    <div className="tabs">
      {tabs.map((tab) => (
        <button
          key={tab.key}
          className={`tab ${active === tab.key ? 'on' : ''}`}
          onClick={() => onChange(tab.key)}
        >
          {tab.label}
        </button>
      ))}
    </div>
  );
}

export function KV({ items }) {
  return (
    <div className="kv">
      {items.map((item) => (
        <div className="kv-row" key={item.label}>
          <span className="kv-key">{item.label}</span>
          <span className={item.mono ? 'mono' : ''}>{item.value ?? '—'}</span>
        </div>
      ))}
    </div>
  );
}
