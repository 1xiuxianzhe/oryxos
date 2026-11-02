# Research: Memory（长期记忆、核心/归档分区、记忆读写工具）

**Phase 0 产出**：开工前的现状事实核对 + 关键技术决策。所有结论都以仓库真实代码/文档为依据，不引用外部方案（业界方案盘点见 `docs/class/第21节`，不属本特性范围）。

## 现状事实（开工前核对，均有代码依据）

- **P-01：记忆模块从未进入运行时 classpath。** 全仓库 `pom.xml` 中，除根 pom 的 `<modules>` 与 `dependencyManagement` 外，**没有任何模块依赖 `oryxos-memory`**。这意味着即使把装配类写好，`oryxos-boot` 的 fat JAR 里也没有它，组件扫描不到。本特性必须补 `oryxos-boot`（以及需要用到格式工具的 `oryxos-cli`）的依赖，否则"接了线也不生效"。
- **P-02：仓库不使用 `@Tool` 注解，Spring AI 自动执行已被显式关闭。** 工具统一是 `OryxTool`（core），在 `ToolAutoConfiguration` 里**显式登记**；`DefaultProviderService` 里 `internalToolExecutionEnabled(false)` 且有测试守护。因此第22节示例中的 `@Tool` 写法按"示意代码"处理，实现改用 `OryxTool`。
- **P-03：core 侧的注入点早已就绪但语义不同。** `PromptBuilder` 第二部分当前调用 `MemoryReader.recall(sessionId, 最后一条用户消息)`，即"用最后一句话做关键词召回"；而 `docs/TechnicalSolution.md` §5.3 与 `website/docs/memory.md` 的结论都是"注入整个 MEMORY.md（截断后）"。本特性按后者修正（见 D-02）。

## 决策

### D-01 门面归属：接口进 core，旧读端口退役（对应 C-001）

**结论**：新增 `io.oryxos.core.memory.MemoryService` 与 `MemoryScope`；删除 `io.oryxos.core.memory.MemoryReader` 及其实现 `DefaultMemoryReader`；`PromptBuilder` 与 `CoreAutoConfiguration` 改为依赖门面。

**理由**：core 不能依赖实现模块，因此门面类型必须住在 core 才能被引擎直接依赖。这与既有 `SessionStore`（端口在 core）↔ `JpaSessionStore`（在 storage）同构，是仓库既有惯例。

**备选与放弃**：保留 `MemoryReader` 作为门面之下的一层转接。放弃原因：多一层无收益的间接，且引擎看到的仍是 `MemoryReader`，与宪法 V 的表述不符。

### D-02 第二部分注入语义：整份长期记忆，不做关键词召回（对应 C-002）

**结论**：`MemoryService.buildContext(Session)` 返回"核心区完整 + 归档区（必要时截断）"；`PromptBuilder` 第二部分直接用它，不再有 `lastUserMessage` 参与。

**理由**：① 技术方案 §5.3 与产品文档的结论都是"注入整个 MEMORY.md"；② 关键词召回只在 Agent 显式调用 `recall_memory` 时发生，把它塞进每轮自动注入会让"记得住谁是谁"变成"记得住最后一句话提过的东西"，两者语义不同；③ 会话历史由第三部分负责，第二部分重复注入会让同一内容出现两次（用户已拍板只注入长期记忆）。

**备选与放弃**：按现实现保留 query 召回。放弃原因：与文档结论冲突，且会让核心记忆（"永远在场"的定位）依赖关键词是否命中。

**保留 `Session` 参数的理由**：扩展阶段"按 Agent/用户隔离记忆"需要它；核心阶段按工作区共享，参数不被读取（在 Javadoc 中说明，避免读者误以为已实现隔离）。

### D-03 文件格式：两个区块标题 + 带日期条目

**结论**：文件骨架为 `# OryxOS Memory` + `## 核心记忆` + `## 归档记忆` 两个区块；条目渲染为 `- [yyyy-MM-dd] 内容`。

**理由**：与第22节的 `MEMORY.md` 内部结构一致；Markdown 二级标题分区最省事，不需要新表或新类（第22节"坑"之一就是别把核心记忆做成新子系统）。

**多行内容处理**：写入时逐行处理——第一行 `- [日期] 首行`，其余行以两个空格缩进续行。这样内容里出现 `## ` `# ` 等 Markdown 标题符号时**不会**被误认成区块标题（FR-014）。

### D-04 截断语义：读取时截断、只裁归档区、不回写文件

**结论**：归档区超过 4000 字符时，**在读取/渲染时**只保留末尾部分；**不修改磁盘文件**。核心区永不参与截断。

**理由**：① 第22节的 `load()` 就是读取时截断，截断函数只接收归档区那一段文本（物理上不可能动到核心区）；② 不写回让文件保持"只追加"，天然规避并发改写的丢数据风险（FR-012/FR-013）；③ 记忆是"成长记录"，磁盘上留全量反而符合"可审计、可回溯"的定位。

**备选与放弃**：保留旧 `LongTermMemory.truncateIfNeeded()` 那种**写回磁盘**的截断。放弃原因：写回是"删除用户数据"，一旦并发读取/写入交错就可能损坏文件，而收益只是省几十 KB 磁盘。

### D-05 写入：单实例串行化 + 自动补骨架 + 自动建目录

**结论**：`LongTermMemory` 内用一把锁串行化"补骨架 + 插入条目"；写入前确保父目录与两个区块存在（缺区块则在文件末尾补齐，不重排已有内容）；条目插入到目标区块的末尾（区块后第一行 `## ` 之前，或文件末尾）。

**理由**：多个会话（CLI/Web/定时）并发 `save_memory` 是真实场景；`StandardOpenOption.APPEND` 只能保证单次写不互相覆盖，补骨架这类"读-改-写"必须串行。跨进程并发不在核心阶段范围内（单进程单工作区）。

**备选与放弃**：文件锁（`FileChannel.lock`）。放弃原因：跨进程锁在 Windows 上行为差异大，而核心阶段没有多进程写同一工作区的场景。

### D-06 错误处理：读失败降级、写失败上报

**结论**：读取路径（文件不存在、路径是目录、IO 异常、编码异常）一律**降级为空内容 + WARN 日志**，绝不抛出（SC-004/FR-010）。写入路径的 IO 失败**向上抛**，由工具层转成失败的 `ToolResult`（Agent 能看到"没记住"）。

**理由**：读失败若抛异常会让整个对话中断——而记忆只是上下文的一部分，不该拥有否决权；写失败若静默吞掉，Agent 会以为记下来了，属于更坏的结果（静默数据丢失）。

**可重试性判定**：写入类失败标记为**可重试**（磁盘瞬时问题）；参数类失败（缺参数、`scope` 非法）标记为**不可重试**（重试一万次也一样）。

### D-07 工具形态：`OryxTool` + 显式登记，不用 `@Tool`（见 P-02）

**结论**：`save_memory` / `recall_memory` 保持为 `OryxTool` 实现，在 `MemoryAutoConfiguration` 的登记器里显式注册进共享 `ToolRegistry`。

**理由**：宪法 III；且 Spring AI 的自动执行一旦漏关，工具会被调两次。全仓库既有 7 个工具都走这条路，记忆工具不应成为例外。

**schema**：`save_memory` → `{content: string(必填), scope: string(可选, core|archival, 缺省 archival)}`；`recall_memory` → `{keyword: string(必填)}`。

### D-08 工具在 Profile 授权中的可见性

**结论**：不改 `ToolResolver` 语义。`Profile.tools` 未声明或为空 → 不限制（现有默认 Agent 模板就是空清单），记忆工具自动可用；显式声明了工具清单的 Agent 必须把 `save_memory`/`recall_memory` 列进去才可用。

**理由**：这是 005 已定的授权语义（空=不限制），改它会影响所有既有 Agent 的可用工具集合——属于破坏性变更，与本特性无关。

### D-09 初始化与迁移规则（对应 C-004）

**结论**：`oryxos init` 在 `<workspace>/memory/MEMORY.md` 创建骨架；**不再**在根目录创建 `MEMORY.md`。对根目录遗留文件按下表处理：

| 场景 | 行为 |
|---|---|
| 根目录无遗留文件 | 什么都不做 |
| 有遗留文件、但无有效内容（全为空白或 `#` 开头的标题行） | 新文件已在规范路径创建；遗留文件**原样保留**（不删除、不重命名），仅 INFO 提示 |
| 有遗留文件、有有效内容，规范路径**为空或不存在** | 逐条把有效行作为归档条目写入规范文件（带日期），遗留文件改名为备份 `MEMORY.md.bak`（重名时加时间戳后缀） |
| 有遗留文件、有有效内容，规范路径**已有非空内容** | **不迁移、不合并**，WARN 提示人工处理；遗留文件保留 |

**理由**：迁移的边界是"绝不动可能导致信息丢失的操作，也绝不覆盖已有内容"。全空遗留文件不值得为它做重命名（多一个 `.bak` 干扰）；有内容时改名是必要的，否则两个 `MEMORY.md` 会长期互相误导。

**幂等性**：四种场景重复执行结果一致（FR-016）。

### D-10 依赖补齐：memory 进 classpath，装配留在能力模块

**结论**：
- `oryxos-memory/pom.xml` 增加 `spring-boot`（对齐 `oryxos-tool` 的做法，支持 `@Configuration`/`@Value` 装配）与 `spring-boot-starter-test`（test）；
- `oryxos-cli/pom.xml` 增加 `oryxos-memory`（初始化要复用文件格式与迁移逻辑）；
- `oryxos-boot/pom.xml` 显式增加 `oryxos-memory`（进 fat JAR、被组件扫描）。

**理由**：见 P-01——不补这一步，前面所有改动在运行时都不生效。显式声明而不是依赖传递，是因为 boot 是"组装模块"，它的依赖清单就是最终交付物的组成部分。

**备选与放弃**：把记忆装配塞进 `CoreAutoConfiguration`。放弃原因：core 不能依赖 memory 模块的实现类；且这与"能力模块自带装配、boot 负责组装"的既有分工冲突。

## 遗留与明确不做

- 向量/语义检索、情景记忆、自动提炼、上下文压力触发整理：扩展阶段，且需要真实的"关键词检索找不准"信号（第21节第十一小节）。
- 记忆的按 Agent/用户隔离：扩展阶段（`buildContext(Session)` 参数已为此预留）。
- 记忆查询端点（CLI 子命令 / Web API）：核心阶段不做，与审计表"只写不查"的处理一致。
- 写 `USER.md` 的任何路径：本特性明确禁止（FR-015）。
