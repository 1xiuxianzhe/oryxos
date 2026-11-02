# Contract: 只读管理平台（/admin）

**Feature**: `009-web-admin` | **Version**: 1.0 | **Date**: 2026-10-21

## 1. 形态约束

| # | 条款 |
|---|---|
| 1.1 | 纯静态：`index.html` + `admin.css` + `admin.js`，放在 `oryxos-web/src/main/resources/static/admin/` |
| 1.2 | MUST NOT 使用构建工具、npm、框架、CDN；断网可用 |
| 1.3 | 单页 + hash 路由（`#/overview` 等），刷新不 404 |
| 1.4 | MUST NOT 出现任何写操作控件（新建/编辑/删除/发送/触发） |
| 1.5 | 所有数据来自 `/api/v1/*`；MUST NOT 内嵌假数据 |
| 1.6 | 内容渲染 MUST 用 `textContent`（会话/记忆内容来自模型与文件，禁止 `innerHTML` 拼接） |
| 1.7 | 请求失败 MUST 显示 `errorCode + message`，MUST NOT 空白或伪装成功 |

## 2. 页面与数据来源

| # | 导航 | hash | 数据来源 | 主要内容 |
|---|---|---|---|---|
| 1 | 概览 | `#/overview` | `/profiles`、`/tools`、`/sessions`、`/info` | 4 张统计卡（Agent/Tool/活跃会话/Provider）、五大核心能力、技术栈 |
| 2 | 会话 | `#/sessions` | `/sessions`、`/sessions/{id}`、`/sessions/{id}/runs` | 会话表 + 点击展开：最近 100 条消息、运行明细（每次提问 / 工具调用 / token 与耗时汇总） |
| 3 | Agent | `#/agents` | `/profiles` | Profile 卡片：描述、provider/model、工具/技能/定时数量 |
| 4 | Provider | `#/providers` | `/providers` | 表：名称、base-url、状态 |
| 5 | Tool | `#/tools` | `/tools` | 表：名称、描述 |
| 6 | Sandbox 白名单 | `#/sandbox` | `/info.sandbox` | 开关状态 + 三类清单**条数** + 说明（不展示内容） |
| 7 | 长期记忆 | `#/memory` | `/memory` | 记忆文本（等宽块）+ 字符数 + 截断说明 |
| 8 | 运行状态 | `#/status` | `/health`、`/info` | 健康状态、版本、Provider 状态、端点清单 |

## 3. 视觉 token（对齐参考图）

| token | 值 | 用途 |
|---|---|---|
| `--bg` | `#0d0d0d` | 页面底色 |
| `--panel` | `#161616` | 侧栏、卡片 |
| `--panel-2` | `#1d1d1d` | 卡片内层、hover |
| `--border` | `#2a2a2a` | 描边、分隔 |
| `--text` | `#ededed` | 主文本 |
| `--muted` | `#8b8b8b` | 次要文本 |
| `--accent` | `#ff6a1a` | 强调：激活导航、统计数字、Logo、能力序号 |
| `--ok` | `#3ecf8e` | 运行状态点 |

**版式**：

- 固定侧栏 208px：顶部 Logo（三道橙色竖杠 + `OryxOS`），下方 8 项导航，底部版本号。
- 主区：标题（`OryxOS` + 状态胶囊 + 版本标签）、一句话定位、虚线分隔、页面内容，最大宽度 1080px。
- 统计卡：4 列网格，大号橙色数字 + 标签 + 灰色说明。
- 能力卡：`3 + 2` 两行，橙色方形序号 + 标题 + 说明。
- 技术栈：胶囊标签（`Java 21`、`Spring Boot 3.x`、`Spring AI Alibaba`、`SQLite`、`Picocli`）。
- 表格：首行表头灰色小字，行间 1px 分隔，hover 变 `--panel-2`；无斑马纹。

## 4. 交互

| 场景 | 行为 |
|---|---|
| 打开 `/admin` | 302 到 `/admin/index.html`，默认概览页 |
| 切换导航 | 更新 hash，仅重绘内容区；不整页刷新 |
| 数据加载中 | 内容区显示"加载中…" |
| 请求失败 | 内容区显示错误块：`errorCode` + `message` |
| 会话行点击 | 展开详情区：上半为消息列表（role 作为标签、content 等宽），下半为运行明细（汇总统计卡 + 每次提问一张卡：提问原文、token、工具调用表、可折叠的逐轮模型调用表） |
| 空数据 | 显示"暂无数据"提示，不显示空表格 |

## 5. Required Verification（人工）

1. 8 个导航全部可切换，每页都有数据或明确的"暂无数据"。
2. 概览统计数字与各页条目数一致。
3. 会话页点击行后能看到消息；长内容换行不破坏布局。
4. 运行明细的汇总数字与手工 `sqlite3` 对账一致（`llm_calls` 行数、`tool_invocations` 行数、token 合计）；
   工具参数/结果超长时显示"（已截断）"，不撑破表格。
5. 故意把后端停掉再刷新：页面显示错误信息，不白屏。
6. 眼睛过一遍：无新建/编辑/删除按钮；无外链字体/脚本请求（DevTools Network 中只有同源请求）。
7. 窄窗口（≈900px）下布局不重叠、不横向滚动。
