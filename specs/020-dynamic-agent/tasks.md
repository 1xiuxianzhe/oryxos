# Tasks: 动态管理 Agent（class-30）

**Spec**: `spec.md` · **Plan**: `plan.md` · **状态**: 全部完成

图例：`[x]` 完成。

---

## Phase 1 · 解析入口（core）

- [x] **T001** 新增 `profile/AgentDefinitionException`（消息可直接展示，供 400 响应使用）
- [x] **T002** `AgentLoader` 抽出 `Derived(Profile, AgentDefinition)`、`derive(Path)`、`deriveFromText(name, dir, markdown)`、`public static isValidName`
- [x] **T003** `loadOne` 改为复用 `derive`，启动扫描的日志与跳过清单行为保持不变
- [x] **T004** 既有 `AgentLoaderTest`（12 例）零回归

## Phase 2 · 目录存储（core）

- [x] **T005** `agent/AgentStore`：`workspaceRoot / agentsDir / archiveDir / agentDir(name) / exists(name)`
- [x] **T006** `createScaffold(name, description)`：`AGENT.md`（模板）+ `scripts/` + `skills/` + `REFERENCE.md`
- [x] **T007** `writeAtomically(file, content)`：同目录临时文件 + `ATOMIC_MOVE`（不支持时降级替换）
- [x] **T008** `archive(name)`：`archive/<name>-<yyyyMMdd-HHmmss>`，同名追加 `-2` / `-3`
- [x] **T009** `deleteTree(dir)`（仅回滚用）
- [x] **T010** `AgentStoreTest`（9 例）：四项齐备、描述含冒号引号不打散 frontmatter、无 tmp 残留、归档不覆盖、删除干净、非法名字

## Phase 3 · 生命周期编排（core）

- [x] **T011** `AgentNotFoundException`（404 语义）+ `AgentGenerationException`（`unavailable` 区分 503/400）
- [x] **T012** `register(name)`：derive → `scheduler.unregisterProfile` → `registry.register` → `scheduler.registerProfile`
- [x] **T013** `create`：查重（注册表 + 目录）→ 脚手架 → register；失败删目录 + 注销
- [x] **T014** `update`：`deriveFromText` 预校验 → 备份 → 原子写 → register；失败逐字恢复旧内容
- [x] **T015** `delete`：注销定时 → 移出注册表 → 归档（顺序固定，不物理删除）
- [x] **T016** `unregister` 幂等 + 运行期跳过原因 `skippedAgents()`
- [x] **T017** `generate`：`LlmClient.call("author", authorProfile, ...)`；缺配置 → 503 且消息点名键；`=== FILE: x ===` 协议 + 剥围栏 + `AGENT.md` 必须可派生
- [x] **T018** `saveFiles`：相对路径校验 → `AGENT.md` 先整体校验 → 先写其它文件、最后 `update`
- [x] **T019** `AgentLifecycleServiceTest`（21 例）：`InOrder` 断言注册/删除顺序、create 回滚、update 逐字不变、旧句柄 `cancel`、generate 503/400、saveFiles 零写入、监听回调保留旧注册

## Phase 4 · 目录监听（core）

- [x] **T020** `WorkspaceWatcher`：建 `agents/` + 注册 key + 守护线程 `take()`
- [x] **T021** 子目录注册/注销 key；`key.reset()==false` 时触发一次判定
- [x] **T022** `processNow` / `handleChange`（包内可见，供测试驱动）+ 300ms 去抖
- [x] **T023** 仅 `AGENT.md` 触发；`.tmp-*`、`scripts/`、`skills/` 忽略；无 `AGENT.md` 不注销
- [x] **T024** 异常隔离（只记日志）+ `destroy()` 关闭服务与线程
- [x] **T025** `WorkspaceWatcherTest`（5 例）：新增/修改/删除/半写/异常不外抛

## Phase 5 · 装配（core + memory）

- [x] **T026** `MemoryService.snapshot(Path agentDir)` + `DefaultMemoryService` 实现（复用 `MemoryLocator.forFile`，异常降级空串）
- [x] **T027** `CoreAutoConfiguration` 装配 `AgentStore` / `AgentLifecycleService` / `WorkspaceWatcher`（协作者缺失给可读错误）
- [x] **T028** `WorkspaceWatcher` 随 Bean 初始化启动；启动时**只**补注册监听键，不回调业务（防双份 cron）
- [x] **T029** `application.yaml` 增加 `oryxos.author.provider` / `oryxos.author.model`（生成 + 新建模板双重用途）

## Phase 6 · Web 端点

- [x] **T030** `AgentAdminDtos`（含 `AgentListView` / `AgentDetail` / `GeneratedFiles` / `WrittenFiles` / `MemoryView` / `AgentSession`）
- [x] **T031** `AgentAdminController`：`GET/POST /agents`、`GET/PUT/DELETE /agents/{name}`
- [x] **T032** `AgentAdminController`：`generate-files`、`files`、`memory`、`session`、`session/messages`
- [x] **T033** `WorkspaceDtos` + `WorkspaceController`：`tree` / `file`(GET) / `file`(POST)
- [x] **T034** 路径穿越防护 `resolveSafe`：绝对路径/盘符、`..`、`toRealPath()` 越界
- [x] **T035** `GlobalExceptionHandler` 增加 `AGENT_NOT_FOUND`(404)、`INVALID_AGENT_DEFINITION`(400)、`GENERATION_UNAVAILABLE`(503)/`GENERATION_FAILED`(400)
- [x] **T036** `AdminPageController` 只保留 `/admin` 重定向（移除并行的 `/admin-react` 入口）
- [x] **T037** `AgentAdminControllerTest`（15 例）+ `WorkspaceControllerTest`（12 例）

## Phase 7 · 前端（整体改造为 React）

- [x] **T038** `admin-web/` Vite + React 工程：`base=/admin/`、产物覆盖 `static/admin`（`emptyOutDir`）、dev proxy `/api → :8080`
- [x] **T039** 删除旧原生 `static/admin/{index.html,admin.js,admin.css}`，`/admin/` 收敛为单入口
- [x] **T040** `App.jsx` 侧边栏布局：顶层「概览 / Agent 列表 / 定时任务」+「OS 运行时」折叠分组（会话 / Provider / Tool / SandBox / 长期记忆 / 工作区）+ 健康灯与版本
- [x] **T041** `src/api.js`（全部端点）+ `src/format.js` + `src/ui.jsx`（Card/Stats/DataTable/Tabs/KV/Tag/Notice）
- [x] **T042** 页面：`Overview`（实时统计 + 能力 + 运行状态 + 端点清单）
- [x] **T043** 页面：`AgentList`（新建/删除/跳过清单）+ `AgentDetail`（基本信息 / 生成 / 文件 / 会话 / 记忆）
- [x] **T044** 页面：`Schedules`（注册态 + 最近运行 + 跳过配置）、`Sessions`（列表 + 消息 + 运行明细）
- [x] **T045** 页面：`Providers`、`Tools`、`Sandbox`、`Memory`、`Workspace`（目录树 + 文件读写）
- [x] **T046** `npm run build` 成功，产物 `index.html + assets/*` 落在 `oryxos-web/src/main/resources/static/admin/`

## Phase 8 · 验证

- [x] **T047** `WebSmokeTest` 断言 `/admin/index.html` 是 React 产物（`id="root"` + `/admin/assets/`），且打包后的 JS 真的可下载；`/admin-react` 返回 404（无并行前端）
- [x] **T048** `WebSmokeTest` 真实上下文端到端：建（201）→ `/agents`、`/profiles` 立即可见 → 工作区树/文件可达 → PUT 改描述生效 → DELETE 归档 → 再查 404
- [x] **T049** 全仓 `mvn -o test` 全绿（core / provider / memory / tool / storage / web / channel-cli / cli / boot）
- [x] **T050** `spec.md` 增加「实现回写」（I-1 ~ I-8），并回写前端"整体改造"口径

---

## 交付物一览

| 模块 | 新增/修改 |
|---|---|
| oryxos-core | 新增 `AgentStore`、`AgentLifecycleService`、`WorkspaceWatcher`、`AgentNotFoundException`、`AgentGenerationException`、`AgentDefinitionException`；改 `AgentLoader`、`MemoryService`、`CoreAutoConfiguration` |
| oryxos-memory | 改 `DefaultMemoryService`（按 Agent 快照） |
| oryxos-web | 新增 `AgentAdminController`、`WorkspaceController`、`AgentAdminDtos`、`WorkspaceDtos`；改 `GlobalExceptionHandler`、`AdminPageController` |
| oryxos-boot | 改 `application.yaml`（`oryxos.author.*`）；改 `WebSmokeTest`（端到端链路） |
| 前端 | `admin-web/`（Vite + React）**整体接管**管理台：产物覆盖 `static/admin/`，旧原生页面删除，`/admin/` 单入口 |
| 规格 | `specs/020-dynamic-agent/{spec,plan,tasks}.md` |
