# Task List: Agent Provider

## Phase 1: Profile管理

### Task 1.1: Profile管理
- **Task ID**: T1.1.1
- **Description**: 创建Profile Java类
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Completed
- **Dependencies**: None
- **Acceptance Criteria**:
  - 包含name、provider、model、temperature等字段
  - 实现getter/setter方法
  - 实现toString方法用于调试

- **Task ID**: T1.1.2
- **Description**: 实现ProfileLoader
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Completed
- **Dependencies**: T1.1.1
- **Acceptance Criteria**:
  - 支持YAML文件解析
  - 支持环境变量占位符
  - 实现配置验证逻辑
  - 错误处理和日志记录

- **Task ID**: T1.1.3
- **Description**: 实现ProfileRegistry
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T1.1.2
- **Acceptance Criteria**:
  - 内存索引管理
  - 按name查找Profile
  - 线程安全实现

### Task 1.2: 配置验证
- **Task ID**: T1.2.1
- **Description**: 全局层配置验证
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T1.1.3
- **Acceptance Criteria**:
  - providers配置验证
  - 依赖关系检查
  - 错误处理

- **Task ID**: T1.2.2
- **Description**: Profile层配置验证
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T1.2.1
- **Acceptance Criteria**:
  - provider引用验证
  - 配置完整性检查
  - 错误处理

- **Task ID**: T1.2.3
- **Description**: 依赖关系验证
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T1.2.2
- **Acceptance Criteria**:
  - 全局层和Profile层依赖验证
  - 循环依赖检测
  - 错误处理

## Phase 2: ProviderService实现

### Task 2.1: ProviderService核心
- **Task ID**: T2.1.1
- **Description**: 创建ProviderService类
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T1.1.3
- **Acceptance Criteria**:
  - 实现chat方法签名
  - 注入必要的依赖

- **Task ID**: T2.1.2
- **Description**: 实现显式映射表
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Completed
- **Dependencies**: T2.1.1
- **Acceptance Criteria**:
  - 维护provider name到ChatModel的映射
  - 支持动态注册
  - 线程安全

- **Task ID**: T2.1.3
- **Description**: 实现chat方法核心逻辑
- **Assignee**: Developer
- **Estimate**: 1.5 days
- **Status**: Completed
- **Dependencies**: T2.1.2
- **Acceptance Criteria**:
  - 按名路由到正确模型
  - 处理工具schema转换
  - 关闭自动执行功能
  - 实现审计记录

- **Task ID**: T2.1.4
- **Description**: 实现工具适配器
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Completed
- **Dependencies**: T2.1.3
- **Acceptance Criteria**:
  - 将OryxTool转换为Spring AI格式
  - 只生成schema，不包含执行逻辑
  - 支持多种工具类型

### Task 2.2: 审计记录系统
- **Task ID**: T2.2.1
- **Description**: 创建LlmCall实体类
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: None
- **Acceptance Criteria**:
  - 包含success、errorMessage等字段
  - 实现JPA注解
  - 实现toString方法

- **Task ID**: T2.2.2
- **Description**: 实现LlmCallRepository
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.2.1
- **Acceptance Criteria**:
  - 实现基本的CRUD操作
  - 支持按sessionId查询

- **Task ID**: T2.2.3
- **Description**: 创建手工建表脚本
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.2.2
- **Acceptance Criteria**:
  - SQLite建表语句
  - 包含所有必要字段
  - 支持索引优化

- **Task ID**: T2.2.4
- **Description**: 实现审计记录逻辑
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.2.3
- **Acceptance Criteria**:
  - 成功调用记录
  - 失败调用记录
  - 性能指标记录

### Task 2.3: 配置管理
- **Task ID**: T2.3.1
- **Description**: 全局层配置（application.yaml）
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: None
- **Acceptance Criteria**:
  - providers配置段
  - 环境变量支持
  - 配置验证逻辑

- **Task ID**: T2.3.2
- **Description**: Profile层配置格式
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.3.1
- **Acceptance Criteria**:
  - provider配置段
  - model参数配置
  - temperature配置

## Phase 3: 集成与测试

### Task 3.1: 单元测试
- **Task ID**: T3.1.1
- **Description**: ProfileLoader单元测试
- **Assignee**: Tester
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T1.1.2
- **Acceptance Criteria**:
  - 合法YAML解析
  - 坏文件处理
  - 环境变量解析

- **Task ID**: T3.1.2
- **Description**: ProviderService单元测试
- **Assignee**: Tester
- **Estimate**: 1 day
- **Status**: Completed
- **Dependencies**: T2.1.3
- **Acceptance Criteria**:
  - 按名路由测试
  - 自动执行关闭测试
  - 审计记录测试

- **Task ID**: T3.1.3
- **Description**: 工具适配器单元测试
- **Assignee**: Tester
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.1.4
- **Acceptance Criteria**:
  - 工具schema转换测试
  - 只翻译不执行测试

- **Task ID**: T3.1.4
- **Description**: 审计系统单元测试
- **Assignee**: Tester
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.2.4
- **Acceptance Criteria**:
  - 记录功能测试
  - 查询功能测试
  - 错误处理测试

### Task 3.2: 集成测试
- **Task ID**: T3.2.1
- **Description**: 端到端调用测试
- **Assignee**: Tester
- **Estimate**: 1 day
- **Status**: Completed
- **Dependencies**: T2.1.3
- **Acceptance Criteria**:
  - Provider调用测试
  - 响应处理测试
  - 错误处理测试

- **Task ID**: T3.2.2
- **Description**: 多Provider切换测试
- **Assignee**: Tester
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.1.2
- **Acceptance Criteria**:
  - Provider切换测试
  - 配置验证测试
  - 错误处理测试

- **Task ID**: T3.2.3
- **Description**: 错误处理集成测试
- **Assignee**: Tester
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.1.3
- **Acceptance Criteria**:
  - Provider不可用测试
  - 调用失败测试
  - 错误记录测试

### Task 3.3: 性能优化
- **Task ID**: T3.3.1
- **Description**: 响应时间优化
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Completed
- **Dependencies**: T2.1.3
- **Acceptance Criteria**:
  - 响应时间达标
  - 性能指标满足要求

- **Task ID**: T3.3.2
- **Description**: 内存使用优化
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.1.3
- **Acceptance Criteria**:
  - 内存使用合理
  - 垃圾回收优化

- **Task ID**: T3.3.3
- **Description**: 并发处理优化
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.1.2
- **Acceptance Criteria**:
  - 并发安全
  - 性能稳定

## Phase 4: 收尾补充（实现过程中发现并完成）

### Task 4.1: 审计表建表与迁移
- **Task ID**: T4.1.1
- **Description**: 修正 schema.sql 的幂等性与列亲和性
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.2.3
- **Acceptance Criteria**:
  - `CREATE TABLE IF NOT EXISTS` 不再因旧表缺列导致启动失败（去掉依赖新增列的索引）
  - 时间列改为整数亲和性，审计记录能正确读回（避免 TEXT 亲和性把时间戳转成字符串）

- **Task ID**: T4.1.2
- **Description**: 新增既有库迁移脚本
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T4.1.1
- **Acceptance Criteria**:
  - `db/migration/` 下提供 llm_calls 补列脚本
  - 全新库无需执行，脚本内含适用场景说明

### Task 4.2: 配置与密钥安全补强
- **Task ID**: T4.2.1
- **Description**: 未解析占位符校验
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T1.2.1
- **Acceptance Criteria**:
  - api-key 仍是 `${...}` 字面量时，启动阶段明确报错（而非把字面量发给模型换回 401）

- **Task ID**: T4.2.2
- **Description**: 密钥改走环境变量
- **Assignee**: Developer
- **Estimate**: 0.25 day
- **Status**: Completed
- **Dependencies**: T4.2.1
- **Acceptance Criteria**:
  - `application.yaml` 中 `api-key: ${DEEPSEEK_API_KEY}`，仓库内无明文密钥

### Task 4.3: 可观测性（日志）
- **Task ID**: T4.3.1
- **Description**: 启动过程分步日志
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.3.1
- **Acceptance Criteria**:
  - 启动过程按 `[启动 N/4]` 分步可见：读配置 → 建映射表 → 加载 Profile → 装配 ProviderService
  - 容器就绪后打印 Provider 就绪摘要

- **Task ID**: T4.3.2
- **Description**: 各组件运行期日志
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T4.3.1
- **Acceptance Criteria**:
  - ChatModel 创建（含 mask 后的 api-key）、Profile 逐个解析、LLM 调用成功/失败、审计写入均有日志

### Task 4.4: 真实调用验收
- **Task ID**: T4.4.1
- **Description**: AskAiIT 真实调用测试
- **Assignee**: Tester
- **Estimate**: 0.5 day
- **Status**: Completed
- **Dependencies**: T2.1.3, T4.2.2
- **Acceptance Criteria**:
  - 用配置文件中的 provider/model 真实提问并断言回答非空
  - 断言 `llm_calls` 落了一条 `success=true`

## Task Management
- **Total Tasks**: 28
- **Pending Tasks**: 0
- **In Progress**: 0
- **Completed**: 28

---

*All tasks implemented and verified (`mvn test` 全绿：27 个单测 + 集成冒烟 harness 就位；真实调用 `AskAiIT` 手动验证通过)。*

*产出物清单与验证记录见 [plan.md](./plan.md) 的「实际交付物（产出记录）」与「验证记录」章节。*