# Agent Provider：对接大模型的核心能力

> **实现状态**：✅ 已完成（核心阶段 · 能力一：对接 LLM）
> 实现计划与产出记录见 [plan.md](./plan.md)，任务状态见 [tasks.md](./tasks.md)

## Feature Short Name
agent-provider

## User Scenarios & Testing

### Primary User Flow
1. **Agent配置阶段**：开发者创建Agent配置文件，指定使用的Provider（如deepseek）和模型参数
2. **运行时调用**：ReAct循环通过ProviderService调用大模型获取决策
3. **结果处理**：Provider返回模型响应，包含可能的工具调用请求
4. **审计记录**：成功/失败的调用都被记录到llm_calls表

### Edge Cases & Error Handling
- Provider名称不存在时抛出明确异常
- 网络调用失败时记录失败审计并向上抛出异常
- 环境变量中的API密钥被正确使用，不在代码中硬编码
- 多个Provider并存时确保路由不串台

### Testing Approach
- **单元测试**：ProfileLoader、ProviderService、工具适配器、审计记录
- **集成测试**：真实网络调用验证（标记为integration）
- **验收测试**：确保所有harness测试通过

## Functional Requirements

### 1. Provider抽象层
- **ProviderService**：核心服务类，提供`chat(sessionId, Profile, Prompt)`方法
- **显式映射**：维护provider name到ChatModel的映射表，不依赖类型扫描
- **关闭自动执行**：禁用Spring AI的自动工具执行功能

### 2. Profile管理
- **Profile类**：包含name、provider、model、temperature等字段
- **ProfileLoader**：启动时加载profiles目录下的YAML文件
- **ProfileRegistry**：内存索引，按name查找Profile
- **配置分层**：
  - 全局层（application.yaml）：声明可用providers及其凭证
  - Profile层（每个Agent的YAML）：指定具体使用的provider和参数

### 3. 工具适配
- **工具翻译**：将OryxTool转换为Spring AI工具schema格式
- **只翻译不执行**：不包含任何执行逻辑，仅生成工具说明

### 4. 审计记录
- **成功记录**：记录provider、model、token用量、耗时
- **失败记录**：记录错误信息，success字段设为false
- **LlmCall实体**：包含success、error_message等字段
- **手工建表**：使用SQLite手工建表脚本，不依赖hibernate自动迁移

### 5. 配置管理
- **环境变量支持**：`${ENV_VAR}`格式从环境变量读取密钥
- **配置验证**：Profile引用的provider必须在全局层存在
- **错误处理**：坏的Profile记录错误但不阻断启动

## Success Criteria

### Functional Success
- ✅ ProviderService能按名正确路由到指定模型
- ✅ 成功和失败的调用都正确记录到llm_calls表
- ✅ 工具schema正确转换，不包含执行逻辑
- ✅ 自动执行功能被禁用
- ✅ 环境变量中的密钥被正确使用

### Performance Success
- ✅ 单元测试运行时间小于1秒
- ✅ 集成测试能成功调用真实模型API
- ✅ ProviderService方法调用延迟在可接受范围内

### Quality Success
- ✅ 所有harness测试通过（单测+集成测试）
- ✅ 代码符合Google格式和阿里编码规约
- ✅ 配置文件无明文密钥
- ✅ 异常信息清晰明确

## Key Entities

### Profile
- name: String - Profile名称
- provider: ProviderConfig - Provider配置
- model: String - 使用的模型名称
- temperature: Double - 温度参数
- tools: List<ToolConfig> - 可用工具配置
- skills: List<SkillConfig> - 技能配置

### ProviderConfig
- name: String - Provider名称（必须在全局层存在）
- model: String - 使用的模型
- temperature: Double - 温度参数

### LlmCall
- id: Long - 主键
- sessionId: String - 关联的会话ID
- provider: String - 使用的Provider名称
- model: String - 使用的模型
- success: Boolean - 调用是否成功
- errorMessage: String - 错误信息（失败时）
- tokenUsage: TokenUsage - Token使用情况
- duration: Long - 调用耗时（毫秒）
- createdAt: Instant - 创建时间

## Assumptions

1. **依赖可用性**：指定的Spring AI Alibaba starter依赖在项目BOM中可用
2. **网络连接**：集成测试需要真实的网络连接和有效的API密钥
3. **环境变量**：运行时环境变量中包含必要的API密钥
4. **SQLite支持**：使用SQLite作为存储，手工维护建表脚本
5. **Spring AI版本**：使用指定版本的Spring AI，API可能需要调整

## Dependencies & Constraints

- **技术栈**：JDK 21 + Spring Boot 3.x + Spring AI Alibaba
- **模块依赖**：oryxos-core（核心引擎）
- **配置约束**：两层配置（全局层+Profile层）
- **安全约束**：密钥通过环境变量传递，不在代码中硬编码
- **审计约束**：必须记录成功和失败的调用

## Risks & Mitigations

- **Provider依赖问题**：提前验证依赖可用性，在验收标准中明确要求
- **自动执行风险**：通过测试确保自动执行被禁用
- **配置错误**：Profile验证确保provider名称存在
- **审计遗漏**：测试覆盖成功和失败的审计记录

## Acceptance Criteria

1. ✅ **Profile加载**：合法YAML全字段解析，坏文件不阻断启动 — `ProfileLoaderTest`（5 例）
2. ✅ **Provider路由**：按名路由不串台，未知名抛异常 — `ProviderServiceTest`（7 例）
3. ✅ **审计记录**：成功/失败都落审计，包含详细错误信息 — `ProviderServiceTest` + `LlmCallRepositoryTest`（2 例）
4. ✅ **工具适配**：正确转换schema，不包含执行逻辑 — `ToolSchemaAdapterTest`（4 例）
5. ✅ **建表验证**：手工建表脚本创建的表结构正确 — `LlmCallRepositoryTest`（走 `db/schema.sql` 建表）
6. ✅ **集成验证**：真实网络调用成功，返回有效响应 — `AskAiIT` 真实提问「1+1等于多少」返回 `2`
7. ✅ **密钥安全**：代码和配置中无明文密钥 — `api-key: ${DEEPSEEK_API_KEY}`，并新增未解析占位符校验

## Next Steps

- [x] 实现Profile相关类（Profile, ProfileLoader, ProfileRegistry）
- [x] 实现ProviderService核心逻辑
- [x] 实现工具适配器和审计记录
- [x] 创建测试用例和验收harness
- [x] 验证所有测试通过（27 个单测全绿）
- [x] 进行集成冒烟测试验证真实调用（`AskAiIT` / `ProviderSmokeIT`）

---

*本规格已实现并验证完毕。产出物清单、实现偏差与验证记录见 [plan.md](./plan.md)；任务状态见 [tasks.md](./tasks.md)。*