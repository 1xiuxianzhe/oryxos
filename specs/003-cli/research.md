# Research: CLI 命令行入口与会话管理

**Date**: 2026-09-28
**Feature**: [spec.md](./spec.md)
**Source**: `docs/class/第18节：CLI 功能概述、实现思路与代码讲解.md`

## Decision 1: 复用现有 CLI 骨架，按命令职责分层

- **Decision**: 保留 `oryxos-cli` 的 Picocli 根入口和已注册的 12 个命令；将命令实现拆为轻命令文件操作/查询与重命令运行时启动两类。
- **Rationale**: 第 18 节明确要求 12 个命令，并要求不需要模型的命令不启动完整 Spring 上下文，`chat`、`serve`、`gateway` 才加载运行能力。当前根入口和子命令注册已存在，增量实现可减少风险。
- **Alternatives considered**:
  - 所有命令统一启动完整 Spring：启动慢，违反轻重命令分流目标。
  - 重新设计另一套 CLI 入口：重复已有代码，增加打包和帮助行为不一致风险。

## Decision 2: CLI 只做输入输出，Agent 处理委托给统一服务

- **Decision**: `CliChannel` 负责 stdin/stdout、提示符、`/quit` 和错误展示；普通消息统一调用 `AgentService`，不在 CLI 中复制 ReAct、Provider、Tool 或 Memory 逻辑。
- **Rationale**: 第 18 节将 CLI 定义为消息进出的门；项目已有 `AgentService` 作为编排入口。当前 `CliChannel` 的 echo 是占位实现，必须替换为服务调用。
- **Alternatives considered**:
  - CLI 直接调用 `ReActLoop`：绕过统一请求编排、审计和失败处理。
  - CLI 自行调用 Provider：违反 core 分层并会导致入口行为分叉。

## Decision 3: Session 复用由 SessionManager 统一负责

- **Decision**: 增加按 `channel + userId + profileName` 查找或创建 Session 的能力，Session ID 生成和三元组索引只存在于 `SessionManager`；Storage 提供按三元组查询的端口实现。
- **Rationale**: 文档要求同一三元组幂等、任一字段变化隔离，并明确禁止入口自行拼接 ID。当前 `SessionManager.create` 每次生成时间戳 ID，无法满足多轮对话和重启恢复。
- **Alternatives considered**:
  - CLI 自己缓存 Session ID：只能解决单进程，不能跨重启，也会破坏其他入口的一致性。
  - 仅按随机 ID 查询：无法保证同一三元组幂等。
  - 用内存全局 Map 代替持久化：重启后历史丢失。

## Decision 4: Session 持久化沿用 Core Port + Storage Adapter

- **Decision**: 保持 `oryxos-core` 仅依赖 `SessionStore` 接口，由 `oryxos-storage` 的 `JpaSessionStore` 实现 SQLite/JPA 持久化；补充三元组查询、JSON 消息序列化和异常传播契约。
- **Rationale**: 现有项目已采用 core 端口、storage 适配的方向；技术方案明确 SQLite + JPA，且不能让 core 反向依赖 storage。
- **Alternatives considered**:
  - Core 直接依赖 `SessionRepository`：违反模块边界。
  - CLI 自己访问 SQLite：入口层承担存储职责，Web 和未来 Channel 无法复用。
  - 依赖 `ddl-auto=update` 做结构演进：SQLite 演进风险高，应使用手工 schema/迁移脚本。

## Decision 5: 重命令使用显式的应用启动配置

- **Decision**: `chat`、`serve`、`gateway` 使用独立的运行时启动协调器加载完整 Spring 能力，并显式覆盖组件扫描、JPA Repository 扫描和 Entity 扫描范围；轻命令不创建该上下文。
- **Rationale**: 第 18 节特别指出 `scanBasePackages` 不会自动覆盖 JPA Repository 和 Entity 扫描，CLI 启动必须保证 Storage 和审计 Bean 真正装配。
- **Alternatives considered**:
  - 只设置普通组件扫描：会出现 Repository 为 0 或实体未发现，Session/审计无法写入。
  - 每个重命令各自启动一套配置：重复且容易造成三种模式行为不一致。

## Decision 6: 先交付核心阶段运行模式，明确后置能力

- **Decision**: `serve` 复用已有 Web 启动边界，`gateway` 复用统一 Agent/Profile/Session 服务；不在本特性实现认证、IM Channel、SSE、WebSocket、复杂编排或新的 Agent 逻辑。
- **Rationale**: 这些能力在项目 Constitution 和第 18 节中属于扩展阶段或其他能力规格。CLI 计划只固化入口和复用边界。
- **Alternatives considered**:
  - 在 CLI 中实现完整 Gateway 路由：扩大范围并重复 Channel/Web 职责。
  - 为 gateway 创建第二个 Agent 引擎：违反统一引擎原则。

## Decision 7: 测试分层，网络模型调用保持可选

- **Decision**: Session 和命令行为用 JUnit 5、Mockito/AssertJ、临时工作区和内存假 Agent 做可重复测试；真实模型测试作为显式手动集成用例，不作为默认 `mvn test` 依赖。
- **Rationale**: 第 18 节要求 SessionManagerTest、SessionRepositoryTest，并指出命令分流与帮助属于进程级人工验收；项目已有真实调用测试的跳过约定。
- **Alternatives considered**:
  - 默认连接真实 Provider：测试不稳定、依赖 API key 和网络。
  - 只测占位输出：无法验证 Session、AgentService 接线和错误传播。

## Existing Gaps Confirmed

1. `oryxos-cli` 已注册 12 个命令，但多数为占位输出。
2. `CliChannel` 已有 stdin/stdout 和 `/quit`，普通消息仍为 echo，未调用 `AgentService`。
3. `ChatCommand` 固定使用 `default`，尚未真正传递 `--profile`。
4. `SessionManager` 当前创建新 Session 时使用时间戳 ID，未按三元组幂等查找。
5. `JpaSessionStore` 支持按 ID 存取，但缺少按三元组查询；保存异常当前只记录日志，不满足失败可见性要求。
6. CLI 当前缺少完整的命令、Channel 和端到端测试。

这些缺口直接映射到本计划的 Phase 1/2 实施任务，不新增模块或依赖。
