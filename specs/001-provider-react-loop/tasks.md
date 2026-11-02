# Task List: Provider 抽象 + ReAct Loop

## Phase 1: Provider抽象层

### Task 1.1: Profile管理
- **Task ID**: T1.1.1
- **Description**: 创建Profile Java类
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: None
- **Acceptance Criteria**:
  - 包含name、provider、model、temperature等字段
  - 实现getter/setter方法
  - 实现toString方法用于调试

- **Task ID**: T1.1.2
- **Description**: 实现ProfileLoader
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Pending
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
- **Status**: Pending
- **Dependencies**: T1.1.2
- **Acceptance Criteria**:
  - 内存索引管理
  - 按name查找Profile
  - 线程安全实现

### Task 1.2: ProviderService实现
- **Task ID**: T1.2.1
- **Description**: 创建ProviderService类
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T1.1.3
- **Acceptance Criteria**:
  - 实现chat方法签名
  - 注入必要的依赖

- **Task ID**: T1.2.2
- **Description**: 实现显式映射表
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T1.2.1
- **Acceptance Criteria**:
  - 维护provider name到ChatModel的映射
  - 支持动态注册
  - 线程安全

- **Task ID**: T1.2.3
- **Description**: 实现chat方法核心逻辑
- **Assignee**: Developer
- **Estimate**: 1.5 days
- **Status**: Pending
- **Dependencies**: T1.2.2
- **Acceptance Criteria**:
  - 按名路由到正确模型
  - 处理工具schema转换
  - 关闭自动执行功能
  - 实现审计记录

- **Task ID**: T1.2.4
- **Description**: 实现工具适配器
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T1.2.3
- **Acceptance Criteria**:
  - 将OryxTool转换为Spring AI格式
  - 只生成schema，不包含执行逻辑
  - 支持多种工具类型

### Task 1.3: 审计记录系统
- **Task ID**: T1.3.1
- **Description**: 创建LlmCall实体类
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: None
- **Acceptance Criteria**:
  - 包含success、errorMessage等字段
  - 实现JPA注解
  - 实现toString方法

- **Task ID**: T1.3.2
- **Description**: 实现LlmCallRepository
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T1.3.1
- **Acceptance Criteria**:
  - 实现基本的CRUD操作
  - 支持按sessionId查询

- **Task ID**: T1.3.3
- **Description**: 创建手工建表脚本
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T1.3.2
- **Acceptance Criteria**:
  - SQLite建表语句
  - 包含所有必要字段
  - 支持索引优化

- **Task ID**: T1.3.4
- **Description**: 实现审计记录逻辑
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T1.3.3
- **Acceptance Criteria**:
  - 成功调用记录
  - 失败调用记录
  - 性能指标记录

### Task 1.4: 配置管理
- **Task ID**: T1.4.1
- **Description**: 全局层配置（application.yaml）
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: None
- **Acceptance Criteria**:
  - providers配置段
  - 环境变量支持
  - 配置验证逻辑

- **Task ID**: T1.4.2
- **Description**: Profile层配置格式
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T1.4.1
- **Acceptance Criteria**:
  - provider配置段
  - model参数配置
  - temperature配置

## Phase 2: ReAct循环机制

### Task 2.1: ReActLoop核心
- **Task ID**: T2.1.1
- **Description**: 创建ReActLoop类
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T1.2.3, T1.3.4
- **Acceptance Criteria**:
  - 实现循环逻辑
  - 支持上下文管理
  - 实现迭代次数控制

- **Task ID**: T2.1.2
- **Description**: 实现上下文管理
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T2.1.1
- **Acceptance Criteria**:
  - 会话历史管理
  - 上下文保留策略
  - 内存优化

- **Task ID**: T2.1.3
- **Description**: 实现终止条件判断
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T2.1.2
- **Acceptance Criteria**:
  - 最大迭代次数检查
  - 工具调用判断
  - 最终响应条件

### Task 2.2: Tool系统
- **Task ID**: T2.2.1
- **Description**: 创建Tool识别逻辑
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T2.1.3
- **Acceptance Criteria**:
  - 从模型响应中识别工具调用
  - 支持多种工具类型
  - 错误处理

- **Task ID**: T2.2.2
- **Description**: 实现ToolExecutor
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T2.2.1
- **Acceptance Criteria**:
  - 工具执行逻辑
  - 结果处理
  - 错误处理

- **Task ID**: T2.2.3
- **Description**: 实现ToolRegistry
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T2.2.2
- **Acceptance Criteria**:
  - 工具注册和管理
  - 工具查找逻辑
  - 线程安全

- **Task ID**: T2.2.4
- **Description**: 实现结果回填机制
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T2.2.3
- **Acceptance Criteria**:
  - 结果整合到上下文
  - 响应生成
  - 格式化输出

### Task 2.3: 错误处理
- **Task ID**: T2.3.1
- **Description**: 实现Provider不可用处理
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T1.2.3
- **Acceptance Criteria**:
  - 错误检测
  - 优雅降级
  - 用户提示

- **Task ID**: T2.3.2
- **Description**: 实现Tool调用失败处理
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T2.2.2
- **Acceptance Criteria**:
  - 错误检测
  - 结果回填
  - 用户提示

## Phase 3: 集成与测试

### Task 3.1: 单元测试
- **Task ID**: T3.1.1
- **Description**: ProfileLoader单元测试
- **Assignee**: Tester
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T1.1.2
- **Acceptance Criteria**:
  - 合法YAML解析
  - 坏文件处理
  - 环境变量解析

- **Task ID**: T3.1.2
- **Description**: ProviderService单元测试
- **Assignee**: Tester
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T1.2.3
- **Acceptance Criteria**:
  - 按名路由测试
  - 自动执行关闭测试
  - 审计记录测试

- **Task ID**: T3.1.3
- **Description**: ReActLoop单元测试
- **Assignee**: Tester
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T2.1.1
- **Acceptance Criteria**:
  - 循环逻辑测试
  - 上下文管理测试
  - 终止条件测试

- **Task ID**: T3.1.4
- **Description**: Tool系统单元测试
- **Assignee**: Tester
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T2.2.1
- **Acceptance Criteria**:
  - 工具识别测试
  - 工具执行测试
  - 结果回填测试

- **Task ID**: T3.1.5
- **Description**: 审计系统单元测试
- **Assignee**: Tester
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T1.3.4
- **Acceptance Criteria**:
  - 记录功能测试
  - 查询功能测试
  - 错误处理测试

### Task 3.2: 集成测试
- **Task ID**: T3.2.1
- **Description**: 端到端对话测试
- **Assignee**: Tester
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T2.1.3, T2.2.4
- **Acceptance Criteria**:
  - 多轮对话测试
  - 上下文理解测试
  - 最终响应测试

- **Task ID**: T3.2.2
- **Description**: 多Provider切换测试
- **Assignee**: Tester
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T1.2.2
- **Acceptance Criteria**:
  - Provider切换测试
  - 配置验证测试
  - 错误处理测试

- **Task ID**: T3.2.3
- **Description**: Tool调用集成测试
- **Assignee**: Tester
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T2.2.2
- **Acceptance Criteria**:
  - 工具调用测试
  - 结果处理测试
  - 错误处理测试

- **Task ID**: T3.2.4
- **Description**: 错误处理集成测试
- **Assignee**: Tester
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T2.3.1, T2.3.2
- **Acceptance Criteria**:
  - Provider不可用测试
  - Tool调用失败测试
  - 循环终止测试

### Task 3.3: 性能优化
- **Task ID**: T3.3.1
- **Description**: 响应时间优化
- **Assignee**: Developer
- **Estimate**: 1 day
- **Status**: Pending
- **Dependencies**: T2.1.1
- **Acceptance Criteria**:
  - 响应时间达标
  - 性能指标满足要求

- **Task ID**: T3.3.2
- **Description**: 内存使用优化
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T2.1.2
- **Acceptance Criteria**:
  - 内存使用合理
  - 垃圾回收优化

- **Task ID**: T3.3.3
- **Description**: 并发处理优化
- **Assignee**: Developer
- **Estimate**: 0.5 day
- **Status**: Pending
- **Dependencies**: T1.2.2, T2.2.3
- **Acceptance Criteria**:
  - 并发安全
  - 性能稳定

## Task Management
- **Total Tasks**: 24
- **Pending Tasks**: 24
- **In Progress**: 0
- **Completed**: 0

---

*This task list is ready for implementation.*