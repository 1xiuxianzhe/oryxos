# Implementation Plan: 动态管理 Agent（class-30）

**Spec**: `specs/020-dynamic-agent/spec.md`

**Baseline**: 本仓现状（29 节已完成"一个目录 = 一个 Agent"）

**目标**: 补齐 ①改目录即改运行时 ②API 录入与手工同路径 ③管理台能管 ④React 前端。

---

## 1. 架构总览

```
                    ┌──────────────────────────── Web (oryxos-web) ───────────────────────────┐
  /api/v1/agents    │ AgentAdminController   Agent CRUD / generate / files / memory / session │
  /api/v1/workspace │ WorkspaceController    tree / read / write（路径穿越防护）                 │
                    └───────────────┬─────────────────────────────────────────────────────────┘
                                    │ 只做校验 + 委托
                    ┌───────────────▼──────────── Core (oryxos-core) ──────────────────────────┐
                    │ AgentLifecycleService   register/create/update/delete/generate/saveFiles  │
                    │   ├─ AgentStore            脚手架 / 原子写 / 归档 / 删除                  │
                    │   ├─ AgentLoader.derive    解析 + 派生 + 校验（不注册，无副作用）          │
                    │   ├─ AgentScheduler        注销 → 注册（幂等，防双份 cron）               │
                    │   ├─ ProfileRegistry       唯一运行时索引                                │
                    │   └─ LlmClient             生成 AGENT.md 内容                            │
                    │ WorkspaceWatcher       WatchService + 300ms 去抖 → 回调 Lifecycle         │
                    └──────────────────────────────────────────────────────────────────────────┘
```

**依赖方向不变**：web → core；core 不感知 web。`AgentLoader` 的 Bean 仍在 `oryxos-provider`（列表校验需要 provider 名），core 用 `ObjectProvider` 注入。

---

## 2. 新增/修改文件清单

### 2.1 oryxos-core

| 文件 | 类型 | 说明 |
|---|---|---|
| `agent/AgentStore.java` | 新增 | 目录存储：脚手架、原子写、归档、回滚删除、名字校验 |
| `agent/AgentLifecycleService.java` | 新增 | 生命周期编排 + `WorkspaceWatcher.AgentChangeListener` 实现 |
| `agent/WorkspaceWatcher.java` | 新增 | WatchService 监听 `agents/` 与子目录，300ms 去抖 |
| `agent/AgentNotFoundException.java` | 新增 | 404 语义（web 层映射） |
| `agent/AgentGenerationException.java` | 新增 | 生成不可用（503）/生成结果非法（400）两种语义，带 `unavailable` 标志 |
| `profile/AgentDefinitionException.java` | 新增 | 目录解析失败的可读异常（替代包内 `FrontmatterException` 外泄） |
| `profile/AgentLoader.java` | 修改 | 抽出 `derive(Path)` / `deriveFromText(name, dir, markdown)` / `isValidName`；`loadOne` 复用 |
| `memory/MemoryService.java` | 修改 | 新增 `snapshot(Path agentDir)`（按 Agent 的记忆快照） |
| `config/CoreAutoConfiguration.java` | 修改 | 装配 `AgentStore` / `AgentLifecycleService` / `WorkspaceWatcher` |

### 2.2 oryxos-memory

| 文件 | 说明 |
|---|---|
| `DefaultMemoryService.java` | 实现 `snapshot(Path)`：`locator.forFile(MemoryFiles.resolve(agentDir)).load()`，异常降级空串 |

### 2.3 oryxos-web

| 文件 | 说明 |
|---|---|
| `controller/AgentAdminController.java` | 新增：`/api/v1/agents` 读写 + generate/files/memory/session |
| `controller/WorkspaceController.java` | 新增：`/api/v1/workspace` tree/read/write |
| `dto/AgentAdminDtos.java` | 新增：请求/响应 record |
| `dto/WorkspaceDtos.java` | 新增：树节点、文件视图 |
| `exception/GlobalExceptionHandler.java` | 修改：映射 `AgentNotFoundException`(404)、`AgentGenerationException`(503/400) |
| `controller/AdminPageController.java` | 修改：只保留 `/admin` 重定向（移除并行的 `/admin-react` 入口） |

### 2.4 前端（工程目录 `admin-web/`，产物接管 `static/admin/`）

管理台**整体**改成 React：原手写的 `index.html + admin.js + admin.css` 删除，构建产物覆盖同一目录，
对外只有一个入口 `/admin/`。

```
admin-web/
  package.json        react + react-dom + vite + @vitejs/plugin-react
  vite.config.js      base: '/admin/'，outDir: ../oryxos-web/src/main/resources/static/admin（emptyOutDir），dev proxy → :8080
  index.html
  src/main.jsx
  src/App.jsx         侧边栏布局（概览 / Agent / 定时任务 + 「OS 运行时」分组）+ hash 路由 + 健康灯
  src/api.js          统一 fetch 封装 + 错误体解析（本仓无 ApiResponse 信封）
  src/format.js       时间 / token / 耗时 / 角色标签
  src/ui.jsx          Card / Stats / DataTable / Tabs / KV / Tag / Notice
  src/styles.css      深色 + 橙色（沿用原管理台视觉）
  src/pages/          Overview、AgentList、AgentDetail、Schedules、Sessions、Providers、Tools、Sandbox、Memory、Workspace
```

---

## 3. 关键实现决策

### D1 · `AgentLoader.derive` 是唯一解析入口

```java
public record Derived(Profile profile, AgentDefinition definition) {}

public Derived derive(Path agentDir);                                  // 读盘
public Derived deriveFromText(String name, Path dir, String markdown); // 不落盘（update/generate 校验）
```

- 抛 `AgentDefinitionException`（消息可直接展示）。
- **不注册**、不改 `definitions()`/`skippedAgents()` 状态：调用方（lifecycle）决定注册时机。
- `loadOne` 改为调用 `derive`，启动扫描行为与既有测试完全一致。

### D2 · 幂等注册（防双份 cron）

`AgentLifecycleService.register(name)` 严格三步：

```java
Derived derived = loader.derive(dir);     // 1. 先解析：非法内容不得影响旧注册
scheduler.unregisterProfile(name);        // 2. 旧 future 必须取消（handles.put 不取消旧的）
registry.register(derived.profile());     // 3. 覆盖索引
scheduler.registerProfile(derived.profile());
```

### D3 · 三条写路径的失败语义

| 操作 | 前置校验 | 失败处理 |
|---|---|---|
| create | 名字合法、注册表与目录都不存在、有可用 provider | 删掉已写目录 + 注销残留注册 |
| update | 先 `deriveFromText` 校验，再写盘 | 写盘后失败 → 逐字恢复旧内容 + 重新注册旧内容 |
| delete | 存在性检查 | 依次：注销定时 → 移出注册表 → 归档；任一步失败不掩盖前一步 |

### D4 · 归档而非删除

`archive/<name>-<yyyyMMdd-HHmmss>`，同名冲突追加 `-2`、`-3`。删除后审计表与归档目录仍完整，可人工取回。

### D5 · 监听器不重复全量扫

启动扫描归 `AgentLoader`（provider 模块）。`WorkspaceWatcher` 只处理增量：

- 监听 `agents/`：子目录出现 → 注册子目录 key + 触发一次 debounce（拷贝目录时 `AGENT.md` 可能已在注册前写完）；子目录消失 → `unregister`。
- 监听每个子目录：仅 `AGENT.md` 变更触发（`.tmp-*`、`scripts/`、`skills/` 忽略）。
- 去抖：`pending` 记时间戳，300ms 后仍是最新戳才处理；单线程调度器。
- 任何异常只记 ERROR + 记录跳过原因，**保留旧注册**。

### D6 · 生成能力

- 走 `LlmClient.call("author", authorProfile, new LlmRequest(systemPrompt, "", List.of(), List.of()))`。
- 配置：`oryxos.author.provider` / `oryxos.author.model`。**任一为空 → 503**，消息点名配置键（绝不发 `model=null`）。
- 输出协议（system prompt 里写死）：

  ```
  === FILE: AGENT.md ===
  <完整 markdown>
  === FILE: REFERENCE.md ===
  <内容>
  ```

- 解析后：必须含 `AGENT.md`，且 `deriveFromText` 能通过，否则 400；路径不得绝对、不得含 `..`。
- **不落盘、不注册**；用户改完再 `POST /agents/{name}/files`。

### D7 · 工作区路径防护

```java
Path candidate = workspaceRoot.resolve(relative).normalize();
if (Path.of(relative).isAbsolute() || !candidate.startsWith(workspaceRoot)) → 400
// 存在则再校验 toRealPath() 仍在 workspaceRoot.toRealPath() 内（拦符号链接）
```

读取：≤ 512KB、UTF-8 严格解码（`CodingErrorAction.REPORT`），否则 400。
写入：`agents/<name>/AGENT.md` 走 `update` 语义；其余原子写。

### D8 · 会话与记忆

- 固定会话三元组：`channel="admin"`、`userId="console"`、`profile=<name>`；`getOrCreate` 天然幂等。
- 记忆：新增 `MemoryService.snapshot(Path agentDir)`，web 只调门面，不认识 `MEMORY.md` 路径。

---

## 4. 端点契约（新增）

| 方法 | 路径 | 请求 | 响应 | 错误 |
|---|---|---|---|---|
| GET | `/api/v1/agents` | — | `{agents:[...], skipped:[...]}` | — |
| GET | `/api/v1/agents/{name}` | — | Agent 详情（含目录、文件清单） | 404 |
| POST | `/api/v1/agents` | `{name, description}` | 201 + 详情 | 400 |
| PUT | `/api/v1/agents/{name}` | `{markdown}` | 详情 | 400/404 |
| DELETE | `/api/v1/agents/{name}` | — | `{name, archivedTo}` | 404 |
| POST | `/api/v1/agents/{name}/generate-files` | `{description}` | `{files:{...}}` | 400/404/503 |
| POST | `/api/v1/agents/{name}/files` | `{files:{path:content}}` | `{written:[...]}` | 400/404 |
| GET | `/api/v1/agents/{name}/memory` | — | `{content, chars, note}` | 404 |
| GET | `/api/v1/agents/{name}/session` | — | `{sessionId, messages:[...]}` | 404 |
| POST | `/api/v1/agents/{name}/session/messages` | `{content}` | `{sessionId, role, content}` | 400/404 |
| GET | `/api/v1/workspace/tree` | — | 树（深度 3 / 每层 200） | — |
| GET | `/api/v1/workspace/file?path=` | — | `{path, content, size, readonly}` | 400/404 |
| POST | `/api/v1/workspace/file` | `{path, content}` | `{path, size}` | 400/404 |

---

## 5. 测试策略

| 层 | 用例要点 |
|---|---|
| `AgentStoreTest` | 脚手架 4 项齐备；原子写不残留 tmp；归档带时间戳且同名追加序号；删除回滚干净；名字校验 5 种非法 |
| `AgentLifecycleServiceTest` | register 幂等（调度器 `unregisterProfile` 先于 `registerProfile`，`InOrder`）；create 失败回滚；update 非法内容磁盘逐字不变；delete 顺序 + 归档存在；generate 无 model → 503 消息含键名；saveFiles 非法 AGENT.md 一个字节不写 |
| `WorkspaceWatcherTest` | 直接调 `handleChange(dir)` 驱动（不依赖 OS 时序）；仅 `AGENT.md` 触发；非法内容保留旧注册；删除目录注销；异常不外抛 |
| `AgentAdminControllerTest` | 200/201/400/404/503 状态与错误体字段；generate 返回 files 结构 |
| `WorkspaceControllerTest` | 4 种越界形态 400；二进制/超大 400；写 `AGENT.md` 走 update |

既有用例零回归：`mvn test` 全绿。

---

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| `WatchService` 在 Windows 上目录删除只报父目录事件 | 父目录事件里对"曾是目录"的名字也触发 debounce，`process()` 用 `Files.isDirectory` 判定存亡 |
| 复制目录时事件先到、`AGENT.md` 后到 | `process()` 发现无 `AGENT.md` 直接返回（不注销）；注册子目录 key 时补一次 debounce |
| 注册表与磁盘不一致（外部 rm -rf） | 每次写端点前以磁盘为准（`derive` 失败 → 400/404），不做缓存 |
| 生成内容带 ``` 围栏 | 逐文件剥离首尾围栏；`deriveFromText` 兜底校验 |
| 前端构建产物污染源码树 | 产物目录 `static/admin` 纳入版本管理（由 Spring 直接托管；旧原生页面已删除，`/admin/` 单入口） |

---

## 7. 实施顺序

1. core 基础：`AgentDefinitionException` + `AgentLoader.derive*`（先让 29 节测试保持绿）
2. core：`AgentStore` → `AgentLifecycleService` → `WorkspaceWatcher` → 装配
3. memory：`snapshot(Path)`
4. web：DTO → Controller → 异常映射 → 静态页入口
5. 后端测试（core + web）
6. 前端 Vite 应用 + 构建产物
7. 全仓 `mvn -DskipTests=false test` + 真实启动冒烟
