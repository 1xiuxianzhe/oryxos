---
description: "Web Service 与第一版管理平台实施任务"
---

# Tasks: Web Service 与第一版管理平台

**Input**: Design documents from `/specs/009-web-admin/`

**Prerequisites**: `plan.md`、`spec.md`、`research.md`、`data-model.md`、`contracts/web-api.md`、`contracts/admin-ui.md`、`quickstart.md`

**Tests**: 本节明确要求测试（`contracts/web-api.md` 第 5 节共 12 项）。

**Status legend**: `[X]` 已完成并验证；`[ ]` 未完成（附原因）。

---

## Phase 1: Setup

- [ ] T001 确认 `oryxos-web` 已有 `spring-boot-starter-web` / `springdoc`，`application.yaml` 已开 virtual thread 与 8080 端口——无需新增依赖
- [ ] T002 [P] 复核 `SessionManager`（list/get/create/archive）、`ProfileRegistry`、`ToolRegistry`、`ProviderModelRegistry`、`SandboxProperties` 的可用方法

## Phase 2: Foundational（阻塞全部用户故事）

- [ ] T003 新增 `web/dto/ErrorBody.java`（三字段 record），`GlobalExceptionHandler` 全部改用 `ErrorBody`
- [ ] T004 500 兜底改为固定话术 `"内部错误"` + ERROR 日志（含 MDC `runId`），不透传 `e.getMessage()`
- [ ] T005 新增 `ProfileNotFoundException` + 映射 404 `PROFILE_NOT_FOUND`；`SessionNotFoundException` 保持
- [ ] T006 [P] `MemoryService` 增加 `snapshot()`；`DefaultMemoryService` 委托 `LongTermMemory.load()`

**Checkpoint**: 错误体统一、记忆可读、编译通过。

---

## Phase 3: User Story 1 - 真实只读 API (Priority: P1)

- [ ] T007 [US1] 重写 `SessionApiController`：创建 / 列表（按 lastActiveAt 倒序）/ 详情（≤100 条）/ 发消息（32KB UTF-8）/ 归档
- [ ] T008 [US1] `AgentApiController`：保留 `/agent/chat`，新增 `/agents/{name}/invoke`（一次性会话，`channel=invoke`，未知 Agent 404）
- [ ] T009 [US1] 重写 `ProfileApiController`：列表 + 详情（真实 `ProfileRegistry`）
- [ ] T010 [US1] 重写 `ProviderApiController`：`OryxosProviderProperties` + `ProviderModelRegistry`，不含 api-key
- [ ] T011 [US1] 重写 `SystemApiController`：health / info（版本、Provider 状态、沙箱摘要、canBuildAgents）
- [ ] T012 [US1] 新增 `MemoryApiController`：GET /memory（经门面，空内容不 500）
- [ ] T013 [P] [US1] DTO 补齐：CreateSessionRequest / SessionSummary / SessionDetail / MessageView / SendMessageRequest / MessageResponse / InvokeRequest / InvokeResponse / ProfileSummary / ProfileDetail / ProviderSummary / ToolSummary / MemorySnapshot / InfoResponse

---

## Phase 4: User Story 2 - 统一错误出口、500 不泄漏 (Priority: P1)

- [ ] T014 [US2] `GlobalExceptionHandlerTest`：400/404/503/504/500 五类映射 + 三字段结构
- [ ] T015 [US2] 专项断言：500 响应不含内部异常串（`jdbc:sqlite`）、类名、堆栈关键字

---

## Phase 5: User Story 3 - 只读管理平台 (Priority: P1)

- [ ] T016 [US3] `static/admin/index.html`：侧栏（Logo + 8 导航）+ 主区骨架
- [ ] T017 [US3] `static/admin/admin.css`：深色 token（`#0d0d0d` / `#161616` / `#ff6a1a` / `#3ecf8e`）、统计卡、能力卡、表格、响应式
- [ ] T018 [US3] `static/admin/admin.js`：hash 路由、`api()` 包装（解析错误体）、8 个页面渲染、`textContent` 安全渲染
- [ ] T019 [US3] `AdminPageController`：`GET /admin` → 302 `/admin/index.html`

---

## Phase 6: User Story 4 - 真实上下文冒烟 (Priority: P2)

- [ ] T020 [US4] `oryxos-boot` 新增 `WebSmokeTest`（`@SpringBootTest(RANDOM_PORT)`，临时 SQLite + 测试 Provider）
- [ ] T021 [US4] 断言：health / info / profiles / tools 可达；`/admin/index.html` 200 text/html；`/admin` 302

---

## Phase 7: 切片测试与收尾

- [ ] T022 [P] `SessionApiControllerTest`：正常 / 空白 / 40KB / 未知会话 / 已归档 / 创建 / 列表排序 / 详情截断 / 归档
- [ ] T023 [P] `AgentInvokeTest`：未知 Agent 404 且零会话、已知 Agent 一次调用 + 渠道正确
- [ ] T024 回填 `AgentApiControllerTest` / `ToolApiControllerTest` 兼容回归（不改语义）
- [ ] T025 文档同步：`第26节` 补「附：仓库实现说明」；`TechnicalSolution.md` §7 补落地说明；`application.yaml` 注释按需
- [ ] T026 固定 JDK 21 + Maven 3.9.9：`mvn -pl oryxos-web -am test` → 仓库根 `mvn test`
- [ ] T027 回填完成状态与验证记录
- [ ] T028 按 `quickstart.md` 人工逐页核对管理平台与 13 个端点 curl（含真模型 invoke）——待使用方执行

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → US1（DTO 与控制器）→ US2（异常）→ US3（前端）→ US4（冒烟）
- US2 与 US1 可并行（不同文件），但 T003 的 `ErrorBody` 是 T004/T014 的前置
- US3 依赖 US1（前端要有真实端点可调）
- US4 依赖 US1 ~ US3（静态资源与端点都要在）

### Parallel Opportunities

- T013（DTO）与 T007 ~ T012 有依赖，但可与 T006（core/memory 改动）并行
- T014 / T015 同文件，不得并行；T022 / T023 不同文件可并行
- T016 / T017 / T018 三个静态文件可并行编辑（互不重叠）

## Notes

- 不新增 Maven 模块、不新增第三方依赖、前端零 CDN
- 不引入认证/SSE/限流（扩展阶段）
- 所有调用仍经 `AgentService`，审计链不变
- 未执行 Git commit（当前目录不是 git 仓库）
