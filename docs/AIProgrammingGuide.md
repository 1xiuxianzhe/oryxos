本文档定义 OryxOS 的 AI 编程实施思路。主体思路是用 Spec-Kit 完成主体开发，把已有的需求文档和技术方案喂给 Spec-Kit，按五大核心能力拆成 5 个 user story 逐步实施；后续增量阶段切换到手动提示词配合 Claude Code。前置阅读《项目篇 OryxOS 业界调研》《OryxOS 需求文档》《OryxOS 技术方案》。本文档讲思路和拆解方法，不绑定具体时间安排，也不展开提示词细节。

本文档以最新技术方案为准：核心阶段交付的是 Agent OS 的运行时内核，Maven 模块为 9 个（技术方案第 10 章），五大核心能力（对接 LLM、ReAct、Memory、Tool、Web Service）作为 5 个 user story 的骨架。

实施总览
1.1 主体思路：Spec-Kit 加手动提示词的混合模式
OryxOS 的 AI 编程实施分两个阶段，两个阶段用不同的协作工具。


主体开发阶段：从零开发 OryxOS 1.0 的五大核心能力。整个项目 9 个 Maven 模块、模块边界清晰、需求文档和技术方案完整。用 Spec-Kit 跑完整的 spec-driven 流程，constitution 加 specify 加 plan 加 tasks 加 implement，保证产出的代码跟需求文档对齐，避免 vibe coding 跑偏。

增量开发阶段：扩展功能、修 bug、加 Plugin Tool 这些都是小颗粒度增量，每个增量 1 到 3 个文件改动。切换到手动提示词配合 Claude Code，因为 Spec-Kit 跑一次完整流程对小增量来说工程开销过大。

这两个阶段的边界很清晰：Spec-Kit 适合大颗粒度 greenfield，手动提示词适合小颗粒度增量。OryxOS 主体开发是前者，社区接力是后者，工具选择跟工作性质匹配。

1.2 跟需求文档加技术方案的关系
实施指引不重写需求和技术方案，而是把已有文档喂给 Spec-Kit。具体对应关系：

需求文档是 Spec-Kit 的 /speckit.specify 输入。Spec-Kit 把需求文档转成 5 个 user story 的 spec（对应五大核心能力）

技术方案是 Spec-Kit 的 /speckit.plan 输入。Spec-Kit 把技术方案转成模块化的实施 plan

需求文档第 3 章设计目标加技术方案第 1.1 节关键技术决策一起组成 constitution.md（非协商原则）

需求文档第 13 章 5 个验收 demo 直接作为 5 个 user story 的 acceptance criteria

已有文档的投入不浪费，Spec-Kit 只是把它们转换成 AI agent 能直接消费的格式。这里有一个要点：技术方案是 /speckit.plan 的输入，所以 plan 里的模块结构必须跟技术方案第 10 章的 9 个模块一致，喂文档时确保用的是最新版技术方案，否则生成的 plan 会按错误的模块数拆分。

1.3 拆解策略：按 user story 拆，不按时间拆
整个 OryxOS 主体开发按 5 个 user story 组织，每个对应一个核心能力。


5 个 user story 不是平行的，它们之间有明确的依赖关系，依赖关系决定推进顺序：

User Story	核心能力	依赖	对应验收 demo
US-1	对接 LLM	无（基础）	（与 US-2 合并）Demo 一
US-2	ReAct 循环	US-1	Demo 一（查天气穿衣）
US-3	Memory 三层记忆	US-2	Demo 二（跨对话记偏好）
US-4	Plugin Tool 体系	US-2（与 US-3 并行）	Demo 三（零代码 PR digest）
US-5	Web Service	前 4 个	Demo 四加五（同步调用、多端点联动）
依赖关系展开：

US-1 是基础，没有 LLM 调用所有 Agent 能力都跑不起来；

US-2 依赖 US-1，ReAct 循环每轮都要调 LLM；

US-3 加 US-4 并行依赖 US-2（Memory 注入 ReAct 的 prompt，Tool 被 ReAct 调用）；

US-5 依赖前 4 个，对外暴露所有能力。

推进顺序：US-1 到 US-2 到（US-3 加 US-4 并行）到 US-5。具体推进的时间投入由项目方根据团队情况决定。本文档按依赖顺序拆，不规定时长。需求文档和技术方案定的核心阶段节奏是 4 周每周 3 小时，5 个 user story 跟这个节奏的对应关系见技术方案第 12 章，本文档不重复。这里只规定推进的逻辑顺序。

需要说明，这套 user story 拆法跟 Spec-Kit 的机制天然契合。Spec-Kit 的 /speckit.tasks 命令本身就是按 user story 组织任务的，每个 user story 成为一个独立的实施 phase，任务之间按依赖排序、可并行的标记出来。OryxOS 按五大核心能力拆成 5 个 user story，正好顺着 Spec-Kit 的工作方式。

Spec-Kit 跟 OryxOS 的匹配度评估
写实施计划之前先回答一个根本问题：Spec-Kit 真的适合 OryxOS 项目吗？这个判断决定整个实施路径，必须先讲清楚。

2.1 Spec-Kit 适合什么场景
Spec-Kit 是 GitHub 开源的 spec-driven development 工具链，是 2026 年增长最快的开发者工具之一，社区采用度很高，支持二十多种 AI coding agent。它把 AI 辅助编码结构化成可重复的 specify 到 plan 到 tasks 到 implement 流程，核心理念是让 spec 成为代码行为的契约和单一事实来源，把 AI agent 从"代码生成器"变成"按规格干活的协作者"，对治 vibe coding。

社区共识它适合的场景：

medium 到 large greenfield 项目（从零开发，工程量中到大，模块跨多个文件夹）；

需求清晰（上游有明确的需求文档或产品决策）；

AI agent 协作（用 Claude Code、Copilot、Cursor 等做主体开发）；

方法论场景（强制 spec-driven 流程，团队能学到工程方法论）。

不适合的场景：小 feature、快速原型、单文件改动（流程开销大于收益）；大型 brownfield 项目改造（legacy 代码上下文太复杂，超出 LLM context limit）；探索性研究项目（需求未定就跑 spec 会反复返工）。

2.2 OryxOS 的匹配度判断
对照 Spec-Kit 适合的场景逐条评估 OryxOS：

greenfield 上，OryxOS 是从零开发的全新项目不是改造现有代码，完全匹配；

规模上，9 个 Maven 模块、五大核心能力清晰，是典型的 medium 规模，完全匹配；

需求清晰上，已有完整的需求文档加技术方案，五大核心能力都有 user story 级别的描述，完全匹配；

AI agent 协作上，OryxOS 本来就是用 Claude Code 做主体开发，完全匹配；

方法论场景上，Spec-Kit 的强制流程让产出对齐需求，对开发者掌握工程方法论很有价值。

结论：Spec-Kit 是 OryxOS 主体开发的最佳工具选择。社区在 brownfield 项目上对 Spec-Kit 有争议，但 OryxOS 是纯 greenfield，这些争议不适用。

2.3 Spec-Kit 的局限和应对
Spec-Kit 有几个公认的局限，要提前知道应对方案。

局限一，流程对小增量过重。Spec-Kit 的完整流程对小改动开销过大，业内有些团队为此在小增量上转向更轻量的方式。应对：OryxOS 主体开发是大颗粒度（9 个模块同时建），开销分摊到所有模块上是合理的，增量阶段的小改动切换到手动提示词，这正是本文档第 6 章讲的两阶段工具切换的核心理由。

局限二，spec 不会自动跟实现同步。如果 AI agent 在 implement 阶段偏离了 spec，spec 文件本身不会更新。应对：每个 user story 实施完成后跑 /speckit.analyze 做跨 artifact 一致性检查，发现漂移立刻修正。analyze 是 Spec-Kit 专门用来防漂移的命令，官方建议在 tasks 之后、implement 之前跑。

局限三，context limit 在大型 brownfield 上失效。十万级文件的 legacy 项目 LLM 看不全。应对：OryxOS 是纯 greenfield，整个项目所有代码加起来还在 LLM context window 内，这个局限不适用。

局限四，Spec-Kit 本身在快速迭代。命令名、artifacts 格式、集成方式都还在变（比如 Claude Code 的集成已经从早期形态演进到 skills 模式）。应对：本文档不锁定 Spec-Kit 具体版本的细节，主线讲思路加节奏，具体命令和安装方式以实施时官方文档为准。

准备阶段
准备阶段是正式实施前的脚手架工作，由项目方完成，产出三份 Spec-Kit artifacts（constitution、spec、plan），让后续每个 user story 的实施都有清晰的依据。



3.1 Spec-Kit 安装加 Claude Code 配置
Specify CLI 是 Spec-Kit 的入口工具（Python 实现，需要 Python 3.11 以上，推荐用 uv 安装）。安装后通过 specify init 初始化 OryxOS 项目的 Spec-Kit 工作区，工作区里有 .specify/memory/constitution.md 以及 spec、plan、tasks 等 artifacts 的目录结构。

Claude Code 是主推的 AI agent，Spec-Kit 官方支持 Claude Code。具体集成方式（早期是 slash 命令，现在 Claude Code 走 skills 模式，初始化时通过参数指定）以官方文档为准。本文档不展开安装步骤细节（随版本变化），实施前给一份环境准备 checklist 即可。

3.2 /speckit.constitution：写 OryxOS 项目宪章
constitution.md 是项目的 non-negotiable principles，所有后续 spec、plan、tasks、implement 都要遵守。OryxOS 的 constitution 从需求文档第 3 章设计目标加技术方案第 1.1 节的关键技术决策提炼，定为以下原则：

原则一：JDK 21 加 Spring Boot 3.x 单体应用，Maven 多模块（9 个），单二进制部署

原则二：五大核心能力（LLM、ReAct、Memory、Tool、Web Service）优先，支撑模块次之；核心阶段交付运行时内核，企业级治理层放扩展阶段

原则三：自实现 ReAct loop，不直接用 Spring AI 的 Agent 抽象

原则四：Spring AI 只用一半。只用它的 Provider 抽象、协议转换和 @Tool 的 schema 生成，禁用它的自动 tool 执行，tool 调度完全由 ReActLoop 加 ToolExecutor 控制。这条单列，因为它是最容易被 AI agent 写错的地方

原则五：Plugin Tool 三档接入，主推 SKILL.md 加 MCP 零代码方式

原则六：核心阶段 SQLite 加 MEMORY.md 文件存储，向量检索放扩展阶段；审计相关的 tool_invocations 和 llm_calls 核心阶段就写入落库

原则七：每个 user story 完成后有可演示 demo，优先级是跑通而非完美

这些原则会在每次 specify 加 plan 加 implement 时被 AI agent 主动引用，保证整个开发过程不偏离 OryxOS 的方向。其中原则四（Spring AI 只用一半）和原则六（审计 day one 落库）是相对容易被 AI agent 忽略、又必须守住的两条，写进 constitution 是为了让 AI agent 每次都看到。

constitution.md 写一次定下来，整个主体开发期间不改。如果中途发现某条原则不对，停下来重新讨论，不允许 AI agent 自己修改 constitution。

3.3 /speckit.specify：把需求文档转成 5 个 user story
/speckit.specify 命令的输入是需求文档，输出是 5 个 user story 的 spec，每个 user story 对应一个核心能力。

5 个 user story 按依赖关系排推进顺序，而不是按重要性。这里要特别说明：US-5 Web Service 排在最后实施，是因为它依赖前四个能力都就绪，不是因为它不重要。恰恰相反，Web Service 是 OryxOS 区别于个人助手项目的关键能力，重要性很高。本文档不用 P1/P2/P3 这种优先级标记，避免被误读成"靠后的可以不做"，只讲依赖顺序：US-1 到 US-2 是基础，US-3 和 US-4 可并行，US-5 收口。

每个 user story 的 acceptance criteria 直接复用需求文档第 13 章 5 个验收 demo，不重新设计：

US-1 加 US-2 对应 Demo 一（查天气穿衣）；

US-3 对应 Demo 二（跨对话记偏好）；

US-4 对应 Demo 三（零代码 PR digest）；

US-5 对应 Demo 四加 Demo 五（Web Service 同步调用加多端点联动）。

/speckit.specify 执行后生成 spec.md，AI agent 据此理解 OryxOS 整体要做什么。跑完后建议跑一次 /speckit.clarify，AI agent 会问几个澄清问题（比如 max iterations 默认值、对话历史截断策略等），这一步可选但推荐。

3.4 /speckit.plan：把技术方案转成实施 plan
/speckit.plan 命令的输入是技术方案加上一步的 spec.md 加 constitution.md，输出是实施 plan。Plan 包含技术栈选型（JDK 21 加 Spring Boot 3.x 加 Spring AI Alibaba 加 SQLite 加 Picocli）、9 个 Maven 模块的职责（对照技术方案第 10 章）、关键技术决策的展开（自实现 ReAct、Spring AI 只用一半的边界、Plugin Tool 三档、SQLite 加 MEMORY.md、审计 day one 落库）、数据流和模块间协作（PromptBuilder 加 ProviderService 加 ToolExecutor 加 MemoryService 三层门面）。

Plan 生成后人工 review 是必要环节。AI agent 可能根据自己对技术方案的理解做了不该做的取舍，几个要重点检查的点：有没有把 Memory 简化成跟 Session 合并（应该是 MemoryService 三层统一门面）；有没有把 Tool 又拆成多个模块（应该是合并的 oryxos-tool 一个模块）；有没有把 SkillLoader 当成 Tool（它应该归 core 的 ContextLoader）；有没有启用 Spring AI 的自动 tool 执行（必须禁用）。Review 通过后 plan.md 锁定。

3.5 准备阶段交付物清单
准备阶段结束时，OryxOS 项目仓库里应该有 .specify/memory/constitution.md（原则集）、spec.md（5 个 user story）、plan.md（技术栈加 9 个模块加技术决策）、项目原始需求文档加技术方案文档（一并放仓库作为来源参考）、Claude Code 加 Specify CLI 配置说明。准备阶段完成后，5 个 user story 的实施依据全部就绪，可以按依赖关系顺序推进。

基于 Spec-Kit 的实施拆解
准备阶段把整体 spec 和 plan 都准备好了，下面按 5 个 user story 拆解具体实施。每个 user story 的拆解结构一致：核心目标、涉及的 Maven 模块、Spec-Kit 任务拆分思路、关键 task 颗粒度、验收 demo。模块名以技术方案第 10 章的 9 模块为准。

4.1 US-1：对接 LLM（核心能力一）
核心目标：让 OryxOS 能调任意主流 LLM，Agent 不感知具体调的是哪家。LLM 调用的复杂度都被 Spring AI Alibaba 吸收，OryxOS 只在它之上做一层薄包装。

涉及的 Maven 模块：oryxos-core（OryxTool 接口、Session、Profile、ContextLoader 等核心抽象）、oryxos-provider（核心能力一）、oryxos-boot（Spring Boot 启动模块）。

Spec-Kit 任务拆分思路。/

speckit.tasks 针对 US-1 拆任务，按依赖关系排序，标记可并行任务。预期产出的 task 大类：

环境搭建类（Maven 多模块骨架 9 个模块、Spring Boot 启动配置、Spring AI Alibaba 依赖）；

核心抽象类（OryxTool 接口、Profile 数据结构、Message 数据结构）；

Provider 实现类（ProviderService 实现、provider name 到 ChatModel 的显式映射、Function Calling 适配）；

配置类（application.yaml 配置至少跑通 DeepSeek 或 Kimi 一个 Provider，配合 ConfigLoader 从环境变量加载 API key）。

这里有一个关键点要写进 task 注意事项：ProviderService 不能靠"扫描容器里所有 ChatModel"来区分 Provider，多 Provider 并存时 Bean 类型相同会有歧义，必须维护 provider name 到 ChatModel 的显式映射（技术方案 3.2）。AI agent 很容易写成类型扫描，要在 task 里点明。

关键 task 。Spec-Kit 倾向 1 到 2 个文件每 task。US-1 大部分 task 符合：各种数据结构定义每个 task 较小，ProviderService 实现可以拆几个子 task（核心服务加 name 映射加 Function Calling 适配加配置加载）。US-1 实施完成后不立刻有 demo，因为它没有用户可见的入口，下一步 US-2 完成后跟 US-1 一起跑 Demo 一。

4.2 US-2：ReAct 循环（核心能力二）
核心目标：实现 Agent 的核心工作机制。即：LLM 思考是否调用工具，调用之后看结果，再决定下一步，直到给出最终响应。ReAct 循环是 OryxOS 最关键的一段代码。

涉及的 Maven 模块：

oryxos-core（ReActLoop、PromptBuilder、ToolExecutor、ContextLoader）

oryxos-tool（一个 HTTP Tool 加 SandboxChecker 简化版，Demo 一需要）

oryxos-channel-cli（CLI Channel，Demo 一需要）

oryxos-cli（oryxos init 加 oryxos chat 命令）。

注意这里 Tool 相关只有一个 oryxos-tool 模块（技术方案已把 builtin/skill/mcp 合并），不再是旧版的多个 tool 模块。

Spec-Kit 任务拆分思路。预期产出的 task 大类：

ReAct 循环类（ReActLoop 主循环、PromptBuilder、ToolExecutor、MAX_ITERATIONS 控制）；

CLI Channel 类（CliChannel、oryxos chat 命令、oryxos init 工作区初始化）；

基础 Tool 类（HTTP Tool、SandboxChecker 简化版只校验 URL 白名单）；

Profile YAML 解析类（SnakeYAML、Profile 校验）；

Session 类（Session 数据结构、SessionManager 内存版，持久化放 US-5）。

关键 task 颗粒度。US-2 是 Spec-Kit 拆分的重点。几个需要拆细的复杂 task：

ReActLoop 主循环（核心循环逻辑精简、约数十行 Java，但工程化部分如错误处理、日志、消息累积、迭代次数控制建议拆 2 到 3 个子 task）；

PromptBuilder 组装（四部分内容即 system prompt 加 Bootstrap 加 Memory 加对话历史加 Tool 列表，建议拆成几个子 task 逐步加入）。

这里再强调一次关键边界（constitution 原则四）：调用 Spring AI 时只用它的协议转换和 schema 生成，禁用它的自动 tool 执行，tool 的实际调度由 ToolExecutor 控制。AI agent 实现 ReActLoop 时很容易顺手启用 Spring AI 的自动执行，导致 tool 被调两次，task 里要明确禁用。

US-1 加 US-2 完成后跑 /speckit.analyze 检查 spec 跟代码一致性。

验收 Demo 一：查天气穿衣。oryxos chat 启动 CLI，用户输入"查一下北京天气并告诉我穿什么"，Agent 通过 ReAct 循环调用 HTTP Tool 拉天气 JSON，根据数据回复穿衣建议，完整对话日志正确累积到 Session，至少跑通一个 Provider（DeepSeek 或 Kimi）。

4.3 US-3：Memory 三层记忆（核心能力三）
核心目标：让 Agent 跨对话保留状态。核心阶段做极简版的两层（会话和长期），用一份 MEMORY.md 文件加两个内置 Tool 实现，让 Agent 主动写入和读取。

涉及的 Maven 模块：oryxos-memory（核心能力三，含 MemoryService 三层门面、LongTermMemory、MemoryTools）。

Spec-Kit 任务拆分思路。

US-3 相对独立，依赖 US-2 但不影响 US-4。预期产出的 task 大类：

MemoryService 门面类（三层统一门面，对 ReAct 循环只暴露一个接口，内部把会话记忆委托给 SessionManager、长期记忆委托给 LongTermMemory）；

LongTermMemory 类（append、load、recallByKeyword、truncateIfNeeded 四个方法，接口预留 recall 带 mode 参数的向量检索升级空间）；

MemoryTools 类（save_memory 加 recall_memory 两个内置 Tool，用 @Tool 注解）；

PromptBuilder 集成类（在 PromptBuilder 里通过 MemoryService 注入记忆，确保不破坏 US-2 跑通的 ReAct 循环）；

MEMORY.md 文件管理类（文件位置、格式约定、超长截断策略）。

关键 task 颗粒度。US-3 的 task 颗粒度较小，整体工程量不大。MemoryService 门面和 LongTermMemory 的方法每个较小，两个 Tool 每个稍大，PromptBuilder 集成是改动型 task 要小心不破坏已有逻辑。US-3 实施完成后跑 /speckit.analyze。

验收 Demo 二：跨对话记偏好。第一次对话告诉 Agent"我项目用 Spring Boot，部署在 K8s 上"，Agent 主动调 save_memory 追加到 MEMORY.md；重启 OryxOS 或新开会话；第二次对话问"帮我看看我的项目能用什么数据库"，Agent 在响应里引用之前记的偏好给出建议。

4.4 US-4：Plugin Tool 体系（核心能力四）
核心目标。让业务方扩展 OryxOS 的能力。Plugin Tool 三档接入：

零代码 SKILL.md 加 MCP 主推

轻代码自写 MCP server

重代码 Java @Tool 注解。

核心阶段做完三档基础设施加内置 Tool 补齐。

涉及的 Maven 模块：

oryxos-tool（补齐文件 Tool 加 Shell Tool、MCP Client、SandboxChecker 完整版、ToolRegistry，三合一模块）；

oryxos-core（SKILL.md 的加载归 ContextLoader，不在 tool 模块）。

Spec-Kit 任务拆分思路。

US-4 跟 US-3 可以并行（都依赖 US-2 但互不依赖）。预期产出的 task 大类：内置 Tool 补齐类（文件 Tool read_file、write_file、list_dir，Shell Tool 带白名单，SandboxChecker 完整实现）；

MCP Client 类（mcp_servers.yaml 解析、McpClientService 启动时连接、tools/list 拉工具、McpToolAdapter 包装成 OryxTool）；

SKILL.md 类（ContextLoader 加载 .oryxos/skills/ 下引用的 SKILL.md 拼接到 system prompt，这部分归 core 不归 tool）；

Profile 升级类（Profile 增加 skills 字段加 mcp_servers 字段）。

关键 task 颗粒度。US-4 的 task 数量较多。几个需要重点拆解的复杂 task：

MCP Client 集成（MCP 协议是 JSON-RPC over stdio 或 SSE，Java 生态成熟度不如 Python，建议先实现 stdio transport 最常用的，SSE 放扩展，stdio MCP Client 建议拆几个子 task：

连接管理、tools/list、tool/call、错误恢复）；

SandboxChecker 完整版（从 US-2 的简化版只校验 URL 扩展到完整版即文件路径白名单加 Shell 命令白名单加 HTTP 域名白名单，建议拆 3 个子 task）

。US-4 实施完成后跑 /speckit.analyze。

验收 Demo 三：零代码 PR digest。业务方写 .oryxos/skills/daily-pr-digest.md 描述任务，在 mcp_servers.yaml 配置 github-mcp（用社区现成的 MCP server），配置一个 Profile 引用这个 Skill 加 MCP server，Agent 启动后能读 SKILL.md 描述、调 github-mcp 拉 PR、汇总成简报，整个过程业务方零代码只写了一份 markdown 加配置。

4.5 US-5：Web Service（核心能力五）
核心目标。把 OryxOS 的所有能力通过 REST API 对外暴露，业务系统通过 HTTP 接入。这是 OryxOS 区别于个人助手项目的关键能力。

涉及的 Maven 模块：

oryxos-web（核心能力五）

oryxos-storage（SQLite 持久化层，Session 持久化从内存版升级，并落 tool_invocations 和 llm_calls 审计表）

oryxos-cli（Picocli 12 个命令补全）

oryxos-core（ConfigLoader、ContextLoader 的 Bootstrap 加载补全）。

Spec-Kit 任务拆分思路。

US-5 依赖前 4 个 user story 都完成，是最后实施的 user story，Spec-Kit 拆解的任务密度最高。预期产出的 task 大类：Web Service 基础类（WebServer 启动加 virtual thread 配置、GlobalExceptionHandler、OpenAPI 文档）；

6 个 ApiController 类（Session 加 Agent 加 Profile 加 Memory 加 Tool 加 System，每个 Controller 一组端点，可并行实现）；

核心 10 个 REST 端点类（会话管理 4 个、Agent 调用 1 个、Profile/Memory/Tool 列表 3 个、health/info 2 个）；

持久化升级类（Session 从内存版升级到 SQLite，SessionRepository，跨重启恢复，以及 tool_invocations 和 llm_calls 审计表的写入）；

配置与上下文类（ConfigLoader 配置密钥加载，ContextLoader 的 Bootstrap 文件加载补全并跟 PromptBuilder 集成）；CLI 完整版（Picocli 12 个命令全部实现）；

工程化类（Logback 加 SLF4J 结构化日志加错误处理）。

注意审计表的写入放在 US-5（constitution 原则六）：tool_invocations 和 llm_calls 核心阶段就落库，不是只放日志，这样可审计的数据地基 day one 就立起来。这一点 AI agent 容易漏掉（觉得日志够了），task 里要明确。

关键 task 颗粒度。US-5 工程量最大。6 个 ApiController 可以并行实现（互不依赖），每个 Controller 1 到 2 个端点；Session SQLite 升级主要是 SessionRepository 加 messages_json 序列化，要小心 Session 状态的迁移；Bootstrap 加载（ContextLoader）跟 PromptBuilder 集成时确保不破坏之前跑通的 ReAct 循环。US-5 完成后跑最后一次 /speckit.analyze，整个主体开发完成。

验收 Demo 四：Web Service 同步调用。外部系统 POST /api/v1/sessions 创建 Session，POST /api/v1/sessions/{id}/messages 发消息，GET 查历史，DELETE 归档，完整链路跑通。

验收 Demo 五：Web Service 多端点联动。外部系统调 GET /info 查健康加 Provider 列表、GET /profiles 列可用 Agent、GET /tools 查可用 Tool、POST /agents/{name}/invoke 无状态调用 Agent、GET /memory 查长期记忆，5 个不同端点协同完成一次业务流程。

4.6 实施过程中的协作模式
5 个 user story 的实施过程中有几个跨 user story 的协作要点。



/speckit.analyze 每个 user story 结束后跑一次，检查 constitution 跟 spec 跟 plan 跟 tasks 跟代码是否一致，发现漂移立刻修正，这是 Spec-Kit 防漂移的核心命令。

AI agent 跑偏 constitution 时主动纠正。看到 Claude Code 生成的代码不符合 constitution（比如用了非 JDK 21 特性、改了 ReAct 实现方式、启用了 Spring AI 自动 tool 执行、把 Tool 又拆成多模块、Provider 用类型扫描），主动让 AI agent 重读 constitution 改正。这几个正是 OryxOS 最容易被写错的点。

跨 task 上下文丢失时回到 spec。Spec-Kit 把代码拆成多个 task 后，AI agent 实施每个 task 时可能不知道前面任务做了什么，定期让它读 spec.md 加 plan.md 加最近的代码。

git commit 标记每个 user story 完成，方便随时回退到稳定状态。

项目交付物
主体开发完成后 OryxOS 1.0 是一个可演示的最小完整 Agent OS 运行时内核，五大核心能力全部跑通。除了核心代码本身，还有几个交付物。

项目主页。OryxOS 作为开源项目需要一个独立的主页作为对外门面，技术栈用 VitePress 或类似静态站点工具，内容讲清楚 OryxOS 是什么、五大核心能力是什么、怎么快速开始。

Spec-Kit artifacts 保留。.specify/ 目录下的 constitution、spec、plan 在主体开发结束后仍然保留在仓库里，作为社区接力的长期参考。

社区文档。API 参考文档、部署运维手册、贡献者指南这些剩余文档作为社区共建项目，由社区贡献者通过 PR 完成。

增量阶段：手动提示词模式
6.1 为什么从 Spec-Kit 切换到手动提示词
主体开发完成后 OryxOS 进入增量阶段。这个阶段的工作性质跟主体开发完全不同：

单次任务颗粒度小（加一个 Channel、补一个 Bug、加一个 Plugin Tool）；

涉及文件少（通常 1 到 3 个）；

不涉及跨模块协作；

上下文是已经存在的代码而非从零设计。

这种工作性质下 Spec-Kit 流程过重，跑一次完整的 constitution 加 specify 加 plan 加 tasks 加 implement 流程，开销大于单次任务的工作量本身。手动提示词配合 Claude Code 更适合：直接打开 Claude Code 描述要做的事，Claude Code 在已有代码上下文里直接修改，改完跑测试没问题就提 PR，不需要正式的 spec 和 plan artifacts。

6.2 增量开发的工作流
增量阶段的典型工作流：

社区贡献者认领一个 issue（主仓库标注 good-first-issue、feature-request、long-term-goal）；

本地 fork 加 clone OryxOS；

用 Claude Code 打开项目跟 Claude 描述要做的改动；

Claude 在已有代码基础上修改、加测试、跑通；提 PR 到主仓库；

项目方 review 加 merge。

这个流程不强制走 Spec-Kit，每个贡献者按自己习惯做就行。对要求严格的大 feature 可以选择走 Spec-Kit，但不强制。

6.3 跟主体阶段 Spec-Kit artifacts 的对接
主体阶段产出的 constitution.md 和 spec.md 在增量阶段仍作为参考文档保留在仓库里：

constitution.md 仍然是非协商原则，社区贡献的代码必须遵守（JDK 21 加 Spring Boot、自实现 ReAct、Spring AI 只用一半、Plugin Tool 三档等）；

spec.md 是核心能力的契约，社区贡献者改某个核心能力时要保证不破坏 spec 里的 acceptance criteria

plan.md 在主体阶段后基本不再更新，技术方案文档作为社区参考保留。

新加 user story 的处理方式：

小 feature 直接手动提示词加 PR；

大 feature（涉及新增 Maven 模块、改 constitution、跨多个核心能力）由项目方决定是否单独跑一次 Spec-Kit specify 到 plan 到 tasks 流程。

风险和注意事项
7.1 Spec-Kit 当前局限
Spec-Kit 还在快速迭代，工具本身变化频繁，使用时几个注意点：

版本锁定（实施前锁定 Specify CLI 一个具体版本号，整个主体开发期间不升级，命令名、artifacts 格式、集成方式可能在版本之间变化）；

官方文档随时查（本文档讲思路加节奏，具体命令和安装方式以实施时官方文档为准）；

community extension 谨慎用（Spec-Kit 有 70 多个社区扩展，主体开发期间只用官方核心命令，不引入 extension 增加不确定性）。

7.2 实施过程中的常见挑战
AI agent 跑偏 constitution。AI agent 可能走捷径生成不符合 constitution 的代码。对策：每次跑完 implement 后人工检查，发现偏离立刻让 AI agent 重读 constitution 修正。OryxOS 最容易被写错的几处是 Spring AI 自动执行没禁用、Provider 用了类型扫描、Tool 被拆成多模块、SkillLoader 当成 Tool、审计表没落库，检查时重点看这几个。

跨 user story 的上下文断裂。AI agent 可能忘记前面 user story 实施时的具体决策。对策：每个 user story 开始前让 AI agent 重读 spec.md 加 plan.md 加最近代码。

/speckit.analyze 被跳过。analyze 是跨 artifact 一致性检查的核心命令，被跳过会导致 spec 跟代码漂移。对策：把 analyze 作为每个 user story 结束的硬性环节，不能省。

MCP server 集成踩坑。Java MCP Client 生态成熟度不如 Python，stdio transport 可能遇到 process 启动失败、stdin/stdout 编码问题。对策：US-4 实施 MCP 前先用一个最简的 MCP server 测试连通性。

Java 工程基础是前提。Spring Boot 加 Maven 加 JPA 不熟会显著拖慢节奏。对策：实施前确保团队成员对 Spring Boot 生态有基本掌握。

总结
OryxOS 的 AI 编程实施分两个阶段。

主体开发阶段用 Spec-Kit。已有的需求文档加技术方案喂给 Spec-Kit，转成 constitution 加 spec 加 plan 加 tasks 等 artifacts。准备阶段一次性把 constitution、spec、plan 准备好，然后按 5 个 user story 的依赖关系顺序实施：US-1 加 US-2 是基础，US-3 跟 US-4 可并行，US-5 在前 4 个完成后收口。每个 user story 完成后有可演示 demo，对应需求文档第 13 章的 5 个验收 demo。

增量阶段切换到手动提示词配合 Claude Code。小颗粒度增量不适合 Spec-Kit 完整流程，社区贡献者用 Claude Code 直接在已有代码上做改动，主体阶段产出的 constitution 加 spec 作为长期参考保留。

Spec-Kit 跟 OryxOS 的契合度很高：纯 greenfield、medium 规模（9 个模块）、需求清晰、AI agent 协作、方法论场景，每条都对得上。而且 Spec-Kit 的 /speckit.tasks 本来就按 user story 组织任务，OryxOS 按五大核心能力拆 5 个 user story 顺着它的工作方式。社区里对 Spec-Kit 在 brownfield 上的批评不适用纯 greenfield 的 OryxOS。

核心策略是已有文档喂给 Spec-Kit，不重写。OryxOS 已经投入了完整的业界调研加需求文档加技术方案，这些是 Spec-Kit 的最佳输入，比从零生成 spec 质量好得多。关键是喂的是最新版文档：模块是 9 个不是 11 个，constitution 要包含 Spring AI 只用一半、审计 day one 落库这些新决策，否则 Spec-Kit 生成的 plan 会按旧结构走偏。

按 user story 拆而不按时间拆，推进顺序是 US-1 到 US-2 到（US-3 加 US-4 并行）到 US-5。具体时间投入由项目方根据团队情况决定，对应的 4 周节奏见技术方案第 12 章。