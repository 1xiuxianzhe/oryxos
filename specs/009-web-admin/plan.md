# Implementation Plan: Web Service 与第一版管理平台

**Branch**: `009-web-admin` | **Date**: 2026-10-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-web-admin/spec.md`

## Summary

把 `oryxos-web` 从"能响应的壳"变成"真实对外门面"，并交付只读管理平台：

1. **去桩**：`Session` / `Profile` / `Provider` / `System` 四个控制器改为真实依赖，新增 `MemoryApiController`。
2. **补齐端点**：会话五端点（含列表与详情）、`/agents/{name}/invoke`、`/memory`、`/info` 扩充。
3. **错误出口收口**：`ErrorBody` record + 500 不再透传内部消息（安全修复）+ `ProfileNotFoundException` → 404。
4. **只读管理平台**：`static/admin/` 三件套（HTML/CSS/JS），深色 + 橙色强调，8 页，零外部依赖。
5. **验收**：切片测试（Controller/异常）+ `WebSmokeTest` 真实上下文冒烟（`*Test` 命名以进入 surefire）。

**不做什么**：认证、SSE/WebSocket、限流、RBAC、Profile/Memory 写操作、`/agents` 增删改、Prometheus。

## Technical Context

**Language/Version**: Java 21；Spring MVC（virtual thread 已在 `application.yaml` 开启）

**Primary Dependencies**: 无新增。已在的 `spring-boot-starter-web`、`springdoc-openapi-starter-webmvc-ui`

**Storage**: 无表结构变更；会话读写复用 `SessionManager` / `SessionStore`

**Testing**: JUnit 5 + Mockito + standalone MockMvc（切片）；`@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`（冒烟，位于 `oryxos-boot`）

**Target Platform**: JDK 21；8080 端口；内网部署（无认证）

**Project Type**: Maven 多模块单体（后端 + 静态前端）

**Constraints**:

- Controller 只做校验/委托/包装，MUST NOT 出现 ReAct / Provider / Tool 直调（宪法 II、III）
- 记忆读取 MUST 经 `MemoryService`（宪法 V）
- 无新增模块、无新增第三方依赖、前端零 CDN
- 错误响应结构全局唯一（`ErrorBody`）

**Scale/Scope**: `oryxos-web` 7 个控制器 + 1 个异常处理器 + 10 个 DTO + 静态前端 3 文件；
`oryxos-core`/`oryxos-memory` 各 1 处小改（`MemoryService.snapshot()`）；测试 4 个新类 + 1 个冒烟类。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | 原则 / 约束 | 判定 | 说明 |
|---|---|---|---|
| I | Java 21 + Boot 3.x 单体、9 模块 | ✅ 通过 | 不新增模块；前端作为 `oryxos-web` 的静态资源 |
| II | 自实现 ReAct | ✅ 通过 | Web 层只调 `AgentService`，不碰循环 |
| III | Spring AI 边界 | ✅ 通过 | 不涉及 Spring AI 自动执行 |
| IV | Tool 统一管理 | ✅ 通过 | `/tools` 读注册表，不硬编码 |
| V | Memory 统一门面 | ✅ 通过 | 新增 `snapshot()` 只读快照，Web 不直接读文件 |
| VI | 审计 day one | ✅ 通过 | 所有调用经 `AgentService`，审计链不变；invoke 用 `channel=invoke` 便于归类 |
| VII | 核心阶段范围 | ✅ 通过 | 不做认证/流式/限流/写操作 |
| 约束 4 | Sandbox | ✅ 通过 | `/info` 仅暴露开关与条数，不暴露白名单内容 |
| 约束 5 | SQLite 表结构 | ✅ 通过 | 零变更 |

## Project Structure

### Documentation (this feature)

```text
specs/009-web-admin/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── web-api.md        # 13 个端点的请求/响应/错误契约
│   └── admin-ui.md       # 8 个页面的数据来源、视觉 token、只读约束
├── checklists/
│   └── requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
oryxos-core/.../core/memory/MemoryService.java          # 改：新增 snapshot()
oryxos-memory/.../memory/DefaultMemoryService.java      # 改：实现 snapshot()（委托 load()）

oryxos-web/src/main/java/io/oryxos/web/
├── controller/
│   ├── SessionApiController.java     # 重写：创建/列表/详情/发消息/归档
│   ├── AgentApiController.java       # 改：保留 /agent/chat + 新增 /agents/{name}/invoke
│   ├── ProfileApiController.java     # 重写：ProfileRegistry 真实数据
│   ├── ProviderApiController.java    # 重写：全局配置 + 装配状态，不含 api-key
│   ├── ToolApiController.java        # 保留（已真实）
│   ├── SystemApiController.java      # 重写：health/info（Provider 状态 + 沙箱摘要）
│   ├── MemoryApiController.java      # 新增：GET /memory（经门面）
│   └── AdminPageController.java      # 新增：/admin → /admin/index.html
├── dto/                               # 新增 10 个 record（见 data-model.md）
└── exception/
    ├── GlobalExceptionHandler.java   # 改：ErrorBody + 500 不泄漏 + 日志
    ├── ErrorBody.java                # 新增
    ├── SessionNotFoundException.java # 保留
    └── ProfileNotFoundException.java # 新增 → 404

oryxos-web/src/main/resources/static/admin/
├── index.html
├── admin.css
└── admin.js

oryxos-web/src/test/java/io/oryxos/web/
├── controller/SessionApiControllerTest.java     # 新增
├── controller/AgentInvokeTest.java              # 新增
├── controller/AgentApiControllerTest.java       # 保留（兼容回归）
├── controller/ToolApiControllerTest.java        # 保留
└── exception/GlobalExceptionHandlerTest.java    # 新增

oryxos-boot/src/test/java/io/oryxos/boot/
└── WebSmokeTest.java                            # 新增：真实上下文 + 静态资源
```

**Structure Decision**: 后端与静态前端都放在 `oryxos-web`（能力五的模块边界），
冒烟测试放 `oryxos-boot`（只有它有完整应用上下文与配置）。

## 实现决策记录

| # | 决策 | 理由 / 代价 |
|---|---|---|
| **D-01** | 全部端点改为真实依赖，删除桩数据 | 管理平台的全部价值建立在"数据是真的"之上；假数据会让集成方做出错误判断 |
| **D-02** | 响应体用 record 而非 `Map.of` | 字段可空性、类型在编译期确定；springdoc 能生成 schema；`Map.of` 还限制了 null 值 |
| **D-03** | 500 统一话术 + ERROR 日志 | 门面的分寸：对外"内部错误"，对内留全量；`runId` 已在 MDC 中，日志可直接关联审计 |
| **D-04** | `MemoryService.snapshot()` 读取记忆 | 宪法 V：不经门面直接读文件的路径一旦开出，后续会有人复制；只读快照语义清晰 |
| **D-05** | invoke 用 `channel=invoke` 的一次性会话 | 与 `web`/`cli`/`scheduler` 区分，审计里一眼看出"这是一次性调用"；不复用调用方会话，符合"无状态"语义 |
| **D-06** | 归档会话不可再发消息（404） | 归档语义是"结束"，允许追加等于让"已归档"变成假状态 |
| **D-07** | 前端 hash 路由 + 单页 | 纯静态、无构建；刷新不 404；导航切换不重新加载整个页面 |
| **D-08** | 前端错误处理读统一错误体 | 显示 `errorCode + message`，避免"白屏 + 控制台报错"这种最差体验 |
| **D-09** | 冒烟测试类名 `*Test` | 父 POM 无 failsafe，`*IT` 不会自动运行；让"装配没炸"每次构建都验一遍 |
| **D-10** | `/admin` 用 302 重定向 | 静态资源目录不会自动处理 `/admin`（`/admin/` 才会），显式重定向最省事且可测 |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 端点从文档的 10 个扩到 13 个 | 管理平台需要会话列表、Profile 详情、Provider 列表；三者都是只读"查"能力 | 为凑"10 个"让页面去猜数据（如用详情端点轮询）——把复杂度推给前端，且列表语义没有端点承载 |
| 给 `MemoryService` 加方法 | 管理平台要展示记忆；门面是唯一合法读路径 | Web 直接读 `MEMORY.md`：绕过宪法 V 的门面，且把"文件式实现"泄漏到 Web 层 |
| 冒烟测试放 `oryxos-boot` 而非 `oryxos-web` | 只有 boot 有完整 `@SpringBootApplication` 与配置 | 在 web 里造一个测试用 `@SpringBootApplication`：会重复配置扫描范围，且测不到真实打包结果 |

## Phase 0 / Phase 1 输出

- 研究结论见 [research.md](./research.md)
- 数据契约见 [data-model.md](./data-model.md) 与 [contracts/web-api.md](./contracts/web-api.md)
- 界面契约见 [contracts/admin-ui.md](./contracts/admin-ui.md)
- 验收指引见 [quickstart.md](./quickstart.md)
