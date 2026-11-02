# Implementation Plan: Provider 抽象 + ReAct Loop

## Feature Overview
实现Provider抽象层和ReAct循环机制，使OryxOS Agent能够进行多轮对话，支持工具调用和上下文理解。

## High-Level Architecture
```
+-------------------+       +-------------------+       +-------------------+
|   User Interface  | <---- |   ReActLoop       | <---- |   ProviderService |
+-------------------+       +-------------------+       +-------------------+
        ^                       |                       |
        |                       |                       |
        |                       v                       v
+-------------------+       +-------------------+       +-------------------+
|   ToolExecutor    | <---- |   ToolRegistry    | <---- |   LLM Provider    |
+-------------------+       +-------------------+       +-------------------+
```

## Implementation Phases

### Phase 1: Provider抽象层 (优先级: High)
**目标**: 实现Provider的统一管理和调用

#### Tasks:
1. **创建Profile相关类** (3天)
   - `Profile`类：包含provider配置、model参数、temperature等
   - `ProfileLoader`：加载YAML配置文件
   - `ProfileRegistry`：内存索引管理

2. **实现ProviderService** (4天)
   - 核心chat方法实现
   - 显式映射表维护
   - 自动执行关闭
   - 工具适配器实现

3. **审计记录系统** (2天)
   - `LlmCall`实体类
   - `LlmCallRepository`接口
   - 审计记录逻辑

4. **配置管理** (2天)
   - 全局层配置（application.yaml）
   - Profile层配置（YAML文件）
   - 环境变量支持

### Phase 2: ReAct循环机制 (优先级: High)
**目标**: 实现多轮对话和工具调用循环

#### Tasks:
1. **ReActLoop核心实现** (5天)
   - 循环逻辑实现
   - 上下文管理
   - 迭代次数控制

2. **Tool识别与执行** (4天)
   - Tool识别逻辑
   - Tool执行器实现
   - 结果回填机制

3. **错误处理** (2天)
   - Provider不可用处理
   - Tool调用失败处理
   - 循环终止条件

### Phase 3: 集成与测试 (优先级: Medium)
**目标**: 确保各组件协同工作

#### Tasks:
1. **单元测试** (3天)
   - ProviderService测试
   - ReActLoop测试
   - 工具适配器测试

2. **集成测试** (2天)
   - 端到端对话测试
   - 多Provider切换测试

3. **性能优化** (2天)
   - 响应时间优化
   - 内存使用优化

## Detailed Task Breakdown

### Phase 1.1: Profile管理 (3天)
- [ ] 创建Profile Java类（包含所有必要字段）
- [ ] 实现ProfileLoader（SnakeYAML解析）
- [ ] 实现ProfileRegistry（内存索引）
- [ ] 配置验证逻辑
- [ ] 错误处理和日志记录

### Phase 1.2: ProviderService实现 (4天)
- [ ] 创建ProviderService类
- [ ] 实现chat方法核心逻辑
- [ ] 实现显式映射表
- [ ] 实现工具适配器
- [ ] 实现自动执行关闭
- [ ] 实现审计记录

### Phase 1.3: 审计系统 (2天)
- [ ] 创建LlmCall实体类
- [ ] 实现LlmCallRepository
- [ ] 创建手工建表脚本
- [ ] 实现审计记录逻辑

### Phase 1.4: 配置管理 (2天)
- [ ] 全局层配置（application.yaml）
- [ ] Profile层配置格式定义
- [ ] 环境变量支持实现
- [ ] 配置验证逻辑

### Phase 2.1: ReActLoop核心 (5天)
- [ ] 创建ReActLoop类
- [ ] 实现循环逻辑
- [ ] 实现上下文管理
- [ ] 实现迭代次数控制
- [ ] 实现终止条件判断

### Phase 2.2: Tool系统 (4天)
- [ ] 创建Tool识别逻辑
- [ ] 实现ToolExecutor
- [ ] 实现ToolRegistry
- [ ] 实现结果回填机制
- [ ] 实现Tool调用失败处理

### Phase 2.3: 错误处理 (2天)
- [ ] 实现Provider不可用处理
- [ ] 实现Tool调用失败处理
- [ ] 实现循环终止条件
- [ ] 实现错误日志记录

### Phase 3.1: 单元测试 (3天)
- [ ] ProfileLoader单元测试
- [ ] ProviderService单元测试
- [ ] ReActLoop单元测试
- [ ] Tool系统单元测试
- [ ] 审计系统单元测试

### Phase 3.2: 集成测试 (2天)
- [ ] 端到端对话测试
- [ ] 多Provider切换测试
- [ ] Tool调用集成测试
- [ ] 错误处理集成测试

### Phase 3.3: 性能优化 (2天)
- [ ] 响应时间优化
- [ ] 内存使用优化
- [ ] 并发处理优化
- [ ] 缓存策略实现

## Dependencies & Risks

### Dependencies
- **oryxos-core**: 核心引擎依赖
- **oryxos-tool**: 工具系统依赖
- **Spring AI Alibaba**: Provider协议转换
- **SQLite**: 审计数据存储

### Risks
- **Provider依赖问题**: 需要提前验证Spring AI Alibaba依赖可用性
- **性能问题**: ReAct循环可能影响响应时间
- **配置复杂性**: 多层配置管理可能增加复杂性
- **测试覆盖**: 需要充分的测试确保可靠性

## Success Metrics
- ✅ 所有单元测试通过
- ✅ 集成测试通过
- ✅ 性能指标达到预期
- ✅ 代码质量符合规范
- ✅ 文档完整

## Resources Required
- **开发人员**: 2人（Provider + ReAct循环）
- **测试人员**: 1人
- **时间**: 约4-6周

## Deliverables
- [ ] Provider抽象层实现
- [ ] ReAct循环机制实现
- [ ] 完整的测试套件
- [ ] 配置文档
- [ ] 部署指南

## Timeline
- **Week 1-2**: Provider抽象层
- **Week 3-4**: ReAct循环机制
- **Week 5**: 集成测试
- **Week 6**: 性能优化和文档

---

*This implementation plan is ready for task creation.*