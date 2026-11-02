# Research: Web Service 与第一版管理平台

**Feature**: `009-web-admin` | **Date**: 2026-10-21

## R-01 错误响应：`Map` 还是 record

现状用 `Map<String, Object>`，问题有三：`Map.of` 不允许 null 值（null 会被 NPE）；
字段类型对调用方不可见；springdoc 生成不出 schema。

**结论**：统一为 `public record ErrorBody(String errorCode, String message, String timestamp)`。
三字段与文档一致，`timestamp` 用 ISO-8601（`Instant.toString()`），不引入格式化依赖。

## R-02 500 兜底怎么"不泄漏"又不丢可诊断性

三种做法：

| 做法 | 问题 |
|---|---|
| 原样返回 `e.getMessage()`（现状） | 泄漏连接串、SQL、内部类名 |
| 返回空消息 | 对接方无法解释"为什么失败"，只能看日志 |
| 固定话术 `"内部错误"` + ERROR 日志（选定） | 对外零信息量但语义明确；对内日志含 `runId`（MDC）可精确定位 |

日志用 `logger.error("[Web] 未处理异常 runId={}", MDC.get("runId"), e)`——
`runId` 由 `AgentService` 注入，可直接与 `agent_runs` 对照。

## R-03 会话列表从哪里来

`SessionManager.list()` 已存在：有存储时读 `SessionStore.list()`，否则返回内存中的会话。
**注意它会顺带把读回的会话放回内存索引**（既有行为），因此列表接口不会造成重复实例。

摘要里 `messageCount` 取 `session.getMessages().size()`；为避免把长历史整段搬进列表，
只返回计数，消息只在详情里给（最近 100 条）。

## R-04 "查历史"为什么合并进详情

文档提到"查历史"与"查会话详情"两件事。若分成 `GET /sessions/{id}` 与
`GET /sessions/{id}/messages` 两个端点，同一份数据两个入口，前端还要处理"取哪个"。

**结论**：详情返回摘要 + 最近 100 条消息，一个端点解决；`limit` 常量固定并写进响应字段
（`messageLimit=100`），不做参数化（核心阶段没有分页需求）。

## R-05 无状态 invoke 的会话策略

三种做法：

| 做法 | 问题 |
|---|---|
| 复用调用方会话 | 与"无状态"矛盾，调用方的对话历史会被塞进业务调用 |
| 每次新建 Session 并持久化 | 会话表会快速膨胀，且这些会话对用户无意义 |
| 每次新建 + `channel=invoke`（选定） | 语义清晰、审计可归类；是否需要清理/归档属后续治理 |

`userId` 允许调用方传入（默认 `anonymous`），便于审计里区分调用来源。

## R-06 `/memory` 的读路径

`MemoryService` 只有 `buildContext(session)` / `remember` / `recall`。
Web 要展示"当前注入上下文里的长期记忆"：

- 直接读 `MEMORY.md`：绕过宪法 V 的门面，且 Web 层多一份文件路径知识。
- 调 `buildContext(session)`：需要伪造一个 Session 才能调用，语义扭曲。
- **新增 `snapshot()`（选定）**：无参只读，语义就是"注入视角的记忆快照"。

页面与响应里都标注"归档区按 4000 字符截断"，避免运维误以为磁盘原文就这么点。

## R-07 沙箱信息展示到什么粒度

参考图有"Sandbox 白名单"导航。可选：

| 粒度 | 风险 |
|---|---|
| 开关 + 三类条数（选定） | 无敏感内容；能回答"沙箱开没开、配了几条" |
| 完整路径/命令/域名列表 | 当前无认证，等于把安全边界公开；且路径会暴露部署结构 |
| 不展示 | 与参考图不一致，且"沙箱到底开没开"是运维第一问题 |

**结论**：条数粒度；等认证落地后再评估展示全文。

## R-08 前端形态与视觉 token

约束：无构建工具、无 CDN（离线可用）、单页、可读。

- **路由**：hash（`#/sessions`），静态托管下刷新不 404。
- **渲染**：原生 `fetch` + 模板字符串，无框架；数据请求统一走 `api()` 包装（解析错误体）。
- **视觉 token**（对齐参考图）：

| token | 值 | 用途 |
|---|---|---|
| `--bg` | `#0d0d0d` | 页面底色 |
| `--panel` | `#161616` | 卡片/侧栏 |
| `--panel-2` | `#1d1d1d` | 卡片内层/悬停 |
| `--border` | `#2a2a2a` | 分隔与描边 |
| `--text` | `#ededed` | 主文本 |
| `--muted` | `#8b8b8b` | 次要文本 |
| `--accent` | `#ff6a1a` | 强调（数字、激活项、Logo） |
| `--ok` | `#3ecf8e` | 运行状态点 |

- **布局**：固定侧栏 208px；主区最大宽度 1080px；统计卡 4 列；能力卡 3+2；技术栈为胶囊。
- **只读约束**：不渲染任何 `button` 用于写操作；会话详情是纯文本渲染（`textContent`，不拼 HTML）——
  记忆与会话内容来自文件与模型，直接 `innerHTML` 会引入 XSS，虽然内网也属于"不必要的风险"。

## R-09 冒烟测试放哪、怎么起

- `oryxos-web` 没有 `@SpringBootApplication`，起真实上下文需要自己造一个 → 会重复配置扫描，且测不到真实打包。
- `oryxos-boot` 有 `OryxosApplication` + `application.yaml` + 全部依赖。
- 但真实配置要求 `DEEPSEEK_API_KEY`：用属性覆盖为测试 Provider（`oryxos.providers[0].*`），
  只创建 ChatModel，不发请求。
- 数据源覆盖到 `target/oryxos-web-it.db`，避免污染工作区 `.oryxos/oryxos.db`。
- 类名用 `*Test`：父 POM 未配 failsafe，`*IT` 在 `mvn test` 下不会执行。

## R-10 测试策略：切片测"委托"，冒烟测"装配"

| 层 | 手段 | 能抓到什么 | 抓不到什么 |
|---|---|---|---|
| 切片（standalone MockMvc） | 替身依赖 + `ArgumentCaptor` | 参数校验、委托次数、错误码、响应结构 | Bean 装配、静态资源打包、JPA 扫描 |
| 冒烟（`@SpringBootTest`） | 真实上下文 + 随机端口 | 装配、扫描、静态资源、端点可达 | 具体分支逻辑（成本高，用切片覆盖） |

两者互补，缺任一层都会留下"单测全绿、serve 起来就炸"的窗口。
