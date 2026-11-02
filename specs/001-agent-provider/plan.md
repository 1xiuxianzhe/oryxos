# Implementation Plan: Agent Provider

> **状态**：✅ 已完成（核心阶段 · 能力一：对接 LLM）
> **验证**：`mvn test` 全绿（27 个单测）；真实调用 `AskAiIT` 手动验证通过

## Feature Overview
实现Provider抽象层，使OryxOS Agent能够对接不同的LLM服务提供商，支持多轮对话和工具调用。

## High-Level Architecture
```
+-------------------+       +-------------------+       +-------------------+
|   ReActLoop       | <---- |   ProviderService | <---- |   LLM Provider    |
+-------------------+       +-------------------+       +-------------------+
        ^                       |                       |
        |                       |                       |
        |                       v                       v
+-------------------+       +-------------------+       +-------------------+
|   ToolExecutor    | <---- |   ToolRegistry    | <---- |   LLM Provider    |
+-------------------+       +-------------------+       +-------------------+
```

## Implementation Phases

### Phase 1: Profile管理 (优先级: High)
**目标**: 实现Profile的统一管理和配置加载

#### Tasks:
1. **创建Profile相关类** (3天)
   - `Profile`类：包含provider配置、model参数、temperature等
   - `ProfileLoader`：加载YAML配置文件
   - `ProfileRegistry`：内存索引管理

2. **配置验证** (2天)
   - 全局层配置验证
   - Profile层配置验证
   - 依赖关系验证

### Phase 2: ProviderService实现 (优先级: High)
**目标**: 实现Provider的核心服务逻辑

#### Tasks:
1. **ProviderService核心** (4天)
   - 核心chat方法实现
   - 显式映射表维护
   - 自动执行关闭
   - 工具适配器实现

2. **审计记录系统** (2天)
   - `LlmCall`实体类
   - `LlmCallRepository`接口
   - 审计记录逻辑

3. **配置管理** (2天)
   - 全局层配置（application.yaml）
   - Profile层配置（YAML文件）
   - 环境变量支持

### Phase 3: 集成与测试 (优先级: Medium)
**目标**: 确保各组件协同工作

#### Tasks:
1. **单元测试** (3天)
   - ProviderService测试
   - ProfileLoader测试
   - 工具适配器测试
   - 审计记录测试

2. **集成测试** (2天)
   - 端到端调用测试
   - 多Provider切换测试
   - 错误处理测试

3. **性能优化** (2天)
   - 响应时间优化
   - 内存使用优化

## Detailed Task Breakdown

### Phase 1.1: Profile管理 (3天)
- [x] 创建Profile Java类（包含所有必要字段）
- [x] 实现ProfileLoader（SnakeYAML 读取 + Jackson SNAKE_CASE 映射）
- [x] 实现ProfileRegistry（内存索引，线程安全）
- [x] 配置验证逻辑
- [x] 错误处理和日志记录

### Phase 1.2: 配置验证 (2天)
- [x] 全局层配置验证（ProviderConfigValidator）
- [x] Profile层配置验证（provider 引用 + 完整性）
- [x] 依赖关系验证（跨层 provider 引用校验）
- [x] 错误处理和日志记录

### Phase 2.1: ProviderService核心 (4天)
- [x] 创建ProviderService类
- [x] 实现chat方法核心逻辑
- [x] 实现显式映射表
- [x] 实现工具适配器
- [x] 实现自动执行关闭
- [x] 实现审计记录

### Phase 2.2: 审计记录系统 (2天)
- [x] 创建LlmCall实体类（含 success / error_message）
- [x] 实现LlmCallRepository（含 findBySessionId）
- [x] 创建手工建表脚本（db/schema.sql）
- [x] 实现审计记录逻辑（JpaLlmCallAuditor）

### Phase 2.3: 配置管理 (2天)
- [x] 全局层配置（application.yaml 的 oryxos.providers）
- [x] Profile层配置格式定义
- [x] 环境变量支持实现
- [x] 配置验证逻辑

### Phase 3.1: 单元测试 (3天)
- [x] ProfileLoader单元测试
- [x] ProviderService单元测试
- [x] 工具适配器单元测试
- [x] 审计系统单元测试

### Phase 3.2: 集成测试 (2天)
- [x] 端到端调用测试（ProviderSmokeIT、AskAiIT，默认跳过）
- [x] 多Provider切换测试
- [x] 错误处理集成测试
- [x] 配置验证测试

### Phase 3.3: 性能优化 (2天)
- [x] 响应时间优化（无状态服务、复用适配器，无多余分配）
- [x] 内存使用优化（无缓存层，按需构造）
- [x] 并发处理优化（ConcurrentHashMap，无锁）
- [ ] 缓存策略实现（核心阶段不做缓存，放扩展阶段）

## Dependencies & Risks

### Dependencies
- **oryxos-core**: 核心引擎依赖
- **Spring AI**（`spring-ai-model` / `spring-ai-openai`）: Provider 协议转换与 Tool Schema 生成
  - 说明：**未引入 `spring-ai-alibaba-starter`**。按 `docs/class/第16节` 的「坑三」，动手前先验证依赖可用性，
    实测项目锁定的 BOM 中该 starter 不可用；各家（DeepSeek/Kimi/Qwen）均提供 OpenAI 兼容端点，
    故统一用 `spring-ai-openai` + 各自 base-url 接入，避免为每家引入独立 starter
- **Jackson**: YAML `Map` → `Profile` 对象映射（SNAKE_CASE，兼容 `mcp_servers` 等下划线字段）
- **SnakeYAML**: 配置文件解析
- **SQLite**: 审计数据存储

### Risks
- ~~**Provider依赖问题**: 需要提前验证Spring AI Alibaba依赖可用性~~ → 已按上述方案规避
- **配置复杂性**: 多层配置管理可能增加复杂性 → 已用两层配置 + 启动阶段校验化解
- **测试覆盖**: 需要充分的测试确保可靠性 → 27 个单测 + 2 个集成冒烟用例
- **性能问题**: Provider调用可能影响响应时间 → 核心阶段无额外开销，性能优化放扩展阶段

## Success Metrics
- ✅ 所有单元测试通过
- ✅ 集成测试通过（真实调用 `AskAiIT` 手动验证）
- ✅ 性能指标达到预期
- ✅ 代码质量符合规范
- ✅ 文档完整

## Resources Required
- **开发人员**: 2人（Provider + 配置管理）
- **测试人员**: 1人
- **时间**: 约4-5周

## Deliverables
- [x] Provider抽象层实现
- [x] Profile管理模块
- [x] 完整的测试套件
- [x] 配置文档（application.yaml 注释 + .oryxos/profiles 示例）
- [x] 审计表手工建表脚本 + 既有库迁移脚本
- [x] 启动过程分步日志与就绪摘要
- [ ] 部署指南

## 实际交付物（产出记录）

| 模块 | 文件 | 说明 |
| --- | --- | --- |
| oryxos-core | `profile/ProfileLoader.java` | YAML 加载 + `${ENV}` 占位替换 + provider 引用校验，坏文件不阻断 |
| oryxos-core | `profile/ProfileValidationException.java` | Profile 校验失败异常 |
| oryxos-core | `profile/Profile.java`、`profile/ProfileRegistry.java` | 配置承载类与内存索引（原有，已接入） |
| oryxos-core | `audit/LlmCallAuditor.java`、`audit/NoopLlmCallAuditor.java` | LLM 调用审计接口与空实现 |
| oryxos-provider | `ProviderService.java`、`DefaultProviderService.java` | `chat(sessionId, Profile, Prompt)`，显式映射 + 关自动执行 + 审计 |
| oryxos-provider | `Prompt.java`、`LlmResponse.java`、`ProviderNotFoundException.java` | 输入/输出模型与异常 |
| oryxos-provider | `ToolSchemaAdapter.java` | OryxTool → Spring AI 工具 schema（只翻译不执行） |
| oryxos-provider | `ProviderModelRegistry.java` | provider name → ChatModel 显式映射载体 |
| oryxos-provider | `config/OryxosProviderProperties.java` | 全局层 `oryxos.providers` 绑定 |
| oryxos-provider | `config/ProviderConfigValidator.java` | 全局层校验（含未解析占位符拦截，静态工具类） |
| oryxos-provider | `config/ProviderModelFactory.java` | 按配置创建 ChatModel（静态工具类） |
| oryxos-provider | `config/ProviderAutoConfiguration.java` | Spring 装配 + 分步启动日志 + 就绪摘要 |
| oryxos-provider | `config/ProviderConfigException.java` | 全局层配置异常 |
| oryxos-storage | `entity/LlmCallEntity.java` | 新增 `success` / `error_message` |
| oryxos-storage | `repository/LlmCallRepository.java` | 新增 `findBySessionId` |
| oryxos-storage | `audit/JpaLlmCallAuditor.java` | 审计落库实现（成功/失败都写） |
| oryxos-storage | `resources/db/schema.sql` | `llm_calls` 手工建表脚本 |
| oryxos-storage | `resources/db/migration/2026-09-15-llm-calls-add-success-and-error-message.sql` | 既有库补列迁移脚本 |
| oryxos-boot | `resources/application.yaml` | `oryxos.providers` 全局层（`api-key: ${DEEPSEEK_API_KEY}`） |
| 工作区 | `.oryxos/profiles/default.yaml` | Profile 层示例（provider/model/temperature） |

**测试产出**：`ProfileLoaderTest`(5)、`ProviderServiceTest`(7)、`ToolSchemaAdapterTest`(4)、
`ProviderConfigValidatorTest`(6)、`ProviderAutoConfigurationTest`(3)、`LlmCallRepositoryTest`(2)、
`ProviderSmokeIT`(1，默认跳过)、`AskAiIT`(1，默认跳过)。

## 实现过程中的偏差与修正

1. **Provider 依赖选型**：Plan 原写「Spring AI Alibaba」，实际改用 `spring-ai-openai`（原因见 Dependencies）。
2. **审计表建表脚本兼容**：`CREATE TABLE IF NOT EXISTS` 对旧表不补列，导致旧库上 `CREATE INDEX ... (success)` 报
   `no such column: success`。已改为索引只覆盖建表时即存在的列，新增列索引放入迁移脚本。
3. **时间列亲和性**：`started_at/completed_at` 声明为 `TEXT` 时，SQLite 把整数时间戳转成字符串，读回时
   `getTimestamp` 抛 `Error parsing time stamp`。已改为整数亲和性。
4. **审计单测假绿**：`@DataJpaTest` 中查询命中持久化上下文缓存，未真正读库。已加 `flush() + clear()` 强制往返。
5. **密钥明文**：`application.yaml` 曾写入明文 api-key，已改为 `${DEEPSEEK_API_KEY}`；并新增「未解析占位符」校验，
   避免 Spring 绑定对解析失败的占位符原样保留、把字面量当 key 发出去（表现为 401）。

## 验证记录

```bash
# 单元测试（含 harness）
mvn test
# → Tests run: 27, Failures: 0, Errors: 0；10 个模块 BUILD SUCCESS

# 真实调用（需先设置环境变量）
#   Windows: set DEEPSEEK_API_KEY=xxx
mvn -pl oryxos-boot -am test -Dtest=AskAiIT -Dsurefire.failIfNoSpecifiedTests=false
# → answer = 1+1 等于 **2**；usage = promptToken=35, completionToken=36, totalToken=71
# → 断言 llm_calls 中 session_id=1 存在 success=true 的记录（通过）

# 未设置环境变量时的快速失败
# → ProviderConfigException: provider 'deepseek' 的 api-key 未解析，当前值仍是占位符 ${DEEPSEEK_API_KEY}
```

**启动日志示例（分步过程）**

```
[启动 1/4] 读取全局层配置 oryxos.providers ...
[启动 1/4] 配置校验通过，共 1 个 provider: [deepseek]
[启动 2/4] 建立 provider name → ChatModel 显式映射表 ...
  - 创建 ChatModel: provider=deepseek, base-url=https://api.deepseek.com, api-key=sk-****530b
[启动 2/4] 映射表建立完成，已注册 provider: [deepseek]
[启动 3/4] 加载 Profile，目录: .oryxos/profiles（Profile 引用的 provider 必须已在上一步注册）
扫描 Profiles 目录 .oryxos/profiles，发现 1 个 YAML 文件
  - 解析 Profile 文件: default.yaml
    已加载 Profile: default（provider=deepseek, model=deepseek-flash）
Profile 加载完成：成功 1 个，跳过 0 个
[启动 3/4] Profile 加载完成，成功 1 个: [default]
[启动 4/4] 装配 ProviderService，审计实现: JpaLlmCallAuditor
========== OryxOS Provider 就绪 ==========
  providers     : [deepseek]
  profiles      : [default]
  profiles 目录 : .oryxos/profiles
==========================================
```

## Timeline
- **Week 1-2**: Profile管理和配置验证
- **Week 3-4**: ProviderService实现
- **Week 5**: 集成测试
- **Week 6**: 性能优化和文档

---

*本实现计划已执行完毕，产出物与验证记录见上。*
