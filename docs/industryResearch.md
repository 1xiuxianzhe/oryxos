这份调研聚焦"Agent OS"这件事本身。先把 Agent OS 是什么讲清楚，再看业界两个最具代表性的开源 Agent OS 项目（OpenClaw 和 Hermes Agent）分别做到了什么、企业用得怎么样、留下了什么空白，然后看 Java 生态在这件事上的位置，最后落到 OryxOS 的定位和愿景。

OryxOS 想做的事是清晰的：一个企业能完全掌控的、Java 原生的、私有可审计的 Agent 统一底座。调研要回答的是，业界已经做了什么，这件事在 Java 生态里为什么还没人做，以及 OryxOS 想把它做成什么样子。

# 一、什么是 Agent OS

## 1.1 一个明确的定义
Agent OS 是运行和管理 AI Agent 的底座系统。它装在用户（或企业）自己的机器上，向上为各类 Agent（运维助手、客服助手、HR 助手、销售助手等）提供统一的运行环境，向下接入模型、渠道、工具、记忆、身份和审计基础设施。

一个合格的 Agent OS 必须具备五件事。

Agent 配置和生命周期管理。能注册、启动、监控、销毁多个 Agent，每个 Agent 有独立的 prompt、模型、工具、渠道、记忆。Agent 在底座上配置出来，不是写代码写出来的。

统一对外渠道接入。IM（企业微信、飞书、钉钉、Slack、Telegram、Discord 等）、邮件、Web、HTTP API，所有 Agent 共用一套渠道层。

统一对内系统接入。LLM Provider、工具（MCP 或插件）、企业 IT 系统、知识库，所有 Agent 共享一套接入层。

统一记忆。跨 Session 的长期记忆、可复用的 Skill 模板、跨 Agent 的知识沉淀。

Tool 调用和沙箱执行。Agent 通过 LLM Function Calling 调用 Tool，Tool 在沙箱里执行，保证安全边界。

这里要把一个容易混的词辨清楚：Agent OS 跟 agent runtime（Agent 运行时）不是一回事。

agent runtime 指的是让单个 agent 跑起来的执行内核，负责 LLM 调用、工具执行、上下文管理、循环控制，上面五件事里的第五件大致属于这一层。Agent OS 的内核确实包含一个 agent runtime，但它在 runtime 之上还要管前四件事：多个 Agent 的生命周期、统一的对外对内接入、统一记忆、以及后面会讲的多租户和审计。

借操作系统的类比说，runtime 像是单个进程的执行环境，Agent OS 像是管理一群进程、调度资源、提供共享服务和治理的那层。一句话，runtime 让一个 agent 跑起来，Agent OS 让一群 agent 在企业里被管起来。

一句话：Agent OS 解决的是"用户要同时跑 N 个 Agent 时，这些 Agent 共享的基础设施层应该长什么样"。

## 1.2 Agent OS 长什么样
部署形态是这样：

用户部署一个 Agent OS 实例（单一服务），装在自己的机器、服务器或 K8s 上。

Agent OS 自带 Web 管理台或 CLI 工具。

用户通过 Web 或 CLI 在 Agent OS 上创建 Agent（上传或者往某个目录存放一段代码）、注册 Tool 和 Skill、配置渠道。

Agent 跑起来后，最终用户通过 IM 或 Web 跟 Agent 对话。

Agent OS 允许自动运行Agent。

业务方关心的事很简单：

写一个 Tool 实现某个具体功能（可以用任何语言）

然后在 Agent OS 上配置一个 Agent 把这个 Tool 用起来。

业务方不需要写 Agent 后端代码，Agent 是配置出来的，不是写出来的。业务方不感知消息从哪来、LLM 怎么调、用户身份怎么管、审计怎么落、上下文怎么续，这些都是 Agent OS 的事。

## 1.3 Agent OS 跟相邻概念的区别
这件事容易混淆，需要专门讲一下边界。


把 Agent OS 跟最容易混的三类东西摆在一起看，区别就清楚了。

| 产物 | 产物形态 | 谁来用 | 部署形态 | 跟 Agent OS 的关系 |
| --- | --- | --- | --- | --- |
| 编排平台（Dify、Coze、扣子） | 一条 workflow | 业务人员、开发者 | 多为 SaaS 或自部署平台 | 可跑在 Agent OS 之上，拿它当后端 |
| 框架（LangChain、Spring AI、LangChain4j） | 代码 | 开发者 | 自己搭运行环境 | 可作为 Agent OS 的底层组件 |
| 大厂中台 / SaaS（Glean、Bedrock AgentCore、Agentforce） | 完整应用 | 企业采购方 | SaaS，绑云生态 | 不同象限，不可私有部署 |
| Agent OS（本文主题） | 配置出来的常驻 Agent | 企业 IT、业务方 | 装在自己机器上的运行时 | 它自己 |
编排平台是 visual workflow builder，业务人员或开发者用拖拽方式把节点拼成一个 workflow，平台负责执行。它的产物是流程，适合做复杂业务流程编排；Agent OS 的产物是配置出来的、常驻服务的 Agent。两者层级不同，编排平台可以跑在 Agent OS 之上，把 Agent OS 当后端。

框架是一组库或 SDK，给开发者用代码写 Agent，产物是代码，运行环境要开发者自己搞定；Agent OS 的产物是一个装好就跑的服务，基础设施一应俱全。框架可以作为 Agent OS 的底层组件，比如 Agent OS 内部用 Spring AI 实现 LLM 调用。

大厂中台和 SaaS 产品是完整应用，绑各自的云生态或协作平台，产品形态是 SaaS，不是装在用户自己机器上的运行时。Agent OS 是开源、可私有部署的底座，不锁生态。

这三个边界划清楚之后，Agent OS 在整个生态里的位置就清楚了。

# 二、业界两个最具代表性的开源 Agent

业界做开源 Agent这件事，目前最具代表性的是两个项目：OpenClaw 和 Hermes Agent。这两个项目的具体架构、社区和安全细节，已经有专门的文章详细拆解过，这里不再展开，只从业界调研、Agent OS 格局的角度，把它们各自代表的取向和合起来呈现的格局讲清楚。

## 2.1 OpenClaw 和 Hermes Agent
OpenClaw 由 PSPDFKit 创始人 Peter Steinberger 在 2025 年 11 月发布，Node.js 实现，MIT 协议。它代表的是消费者级、开发者优先的取向：二十多个渠道、上万个社区 skill、极强的可玩性，到 2026 年 4 月突破 30 万 GitHub stars，是 GitHub 历史上增速最快的开源项目。它的强项是社区活力和能力丰富度，软肋是企业级安全和治理，CVE、恶意 skill、凭证收割等结构性问题反复出现，企业部署要靠 Tank OS 这类二次加固才能勉强上生产。一句话，OpenClaw 是个人和小团队的 Agent OS。

Hermes Agent 由开源 AI 实验室 NousResearch 在 2026 年 2 月发布，Python 实现，MIT 协议。它代表的是工程级、健壮性优先的取向：三层记忆、自我进化的 skill 机制、安全扫描、HERMES_HOME 多用户隔离，企业级方向投入明显，有商业化样本和云厂商背书，到 2026 年 5 月约 15 万 stars，且在日活推理量上一度反超 OpenClaw。它比 OpenClaw 更接近企业，但企业级 OS 治理（多租户 RBAC、SSO、完整审计）仍是空白。一句话，Hermes 是更偏团队和企业的 Agent OS。

两个项目合起来，基本就勾勒出了当前开源 Agent OS 的格局：一个偏消费级可玩、一个偏工程级健壮，都从个人和小团队起步，产品形态都是"装在自己机器上、提供完整 Agent 运行环境的底座"。这个格局的意义不在于它们各自做了什么，而在于它们合起来留下了什么空白，而那个空白正是理解 OryxOS 定位的起点。

## 2.2 两者共同留下的空白
落到空白上，开源 Agent OS 领域有几个真正没被填的位置。


第一，完整的企业级治理。OpenClaw 和 Hermes 都偏个人到小团队定位，真正企业级的多租户 RBAC、SSO 接入、审计架构、合规留证还是空白。PTG 和 Tank OS 这种二次加固方案存在，但它们是补丁，不是 day one 设计。

第二，企业 IT 系统的深度集成。两个项目对企业 IM 渠道（飞书、企业微信、钉钉）都通过社区扩展支持，但企业现有 IT 系统（ERP、CRM、CMDB、监控系统）的深度对接还是空白，需要每家自己写适配。

第三，Java 生态的缺位。OpenClaw 是 Node.js，Hermes 是 Python。Java 生态里没有任何一个项目把"Agent OS"作为定位。这一点后面单独讲，因为它对企业市场尤其重要。

# 三、企业为什么需要一个私有可控的 Agent 底座

这一章是整份调研论证的重心。这里要把一个判断讲清楚：企业真正的刚需，不是"Agent OS 这个品类"，而是"一个私有、可控、可审计的 Agent 统一底座"。这两者听起来像，但锚点完全不同。前者是一个可能演变的概念，后者是一个不会变的需求。

## 3.1 大盘需求是真实的
业界对企业 AI Agent 的需求已经形成共识。Anthropic 2026 State of AI Agents 报告调研 500 多位技术领导者，超过半数已经部署多步骤 agent workflow，绝大多数计划在 2026 扩展更复杂的用例，多数报告 AI Agent 投资带来可衡量的经济回报。需求侧没有悬念。

## 3.2 但真正的难点不在"做出一个 Agent"，在"让它在企业里可控地跑起来"
同一批数据里有一个反差：大量 agent pilot 永远到不了 production，相当比例的 enterprise pilot 无法 scale。数据矛盾的背后是同一件事。单个 Agent 容易做出 demo，但一旦要做成企业级、跑在生产环境、服务全公司，就立刻撞上一堆工程问题。

Anthropic 报告里最硬的数据指向的正是这些工程问题：企业认为最大的挑战是集成、是数据访问质量、是实施成本、是变更管理。安全治理上，多数组织在过去一年报告过 AI agent 安全事件，而只有很小比例的组织在 agent 进入生产时有完整的 IT/security approval。Tool calling 在生产环境有不低的失败率，且多数是静默失败。

这些问题，没有一个是"模型不够强"能解决的，全都是"底座不够稳、不够可控"的问题。

## 3.3 严监管企业的刚需是确定的、不会变的
把镜头对准最硬的那批客户，也就是银行、政府、电信、能源、医疗。这些严监管企业有几条铁律：

核心业务的数据不能出企业

系统必须完全可审计

任何新组件都要过现有的安全和合规流程

技术栈要跟现有体系对齐。

在这几条铁律下，他们的选择被极大地收窄了。他们不会把核心业务的 Agent 跑在 SaaS 上（数据出企业），不会跑在绑定某个公有云的产品上（锁生态），也很难直接把一个有 CVE 和凭证收割史、默认权限宽松的 Node.js 开源项目放进生产（过不了安全审查）。他们需要的是一个私有部署、完全可审计、能纳入现有 IT 治理、跟现有技术栈对齐的 Agent 底座。

这个需求是确定的、刚性的、且当前无人满足的。而且关键在于：无论"Agent OS"这个词将来流行不流行、这个中间层最后是独立存在还是被某一层吸收，"严监管企业要一个自己能完全掌控的 Agent 底座"这件事都不会变。 这是一个锚在企业本质约束上的需求，不是锚在一个技术概念上的需求。

## 3.4 价值锚点
这个私有可控的底座，对企业的价值是具体的：直接降本、直接增效、跨系统协同、合规与审计可落地、让普通员工也能用上 AI 能力。

而最深的一个价值锚点是知识沉淀。通过 Skill 体系，把高级员工的经验资产化、留在企业，新员工可以继承。沉淀的知识、经验、AI 能力，永远留在企业，这是私有底座区别于 SaaS 的根本价值。

# 四、Java 生态在这件事上的位置

## 4.1 Java 生态现状：有框架，没有底座
Java 在 AI 工程领域有项目，但没有任何一个是 Agent OS 这一层的底座。

Spring AI（Spring 官方推出）是 Java AI 应用开发框架，做 LLM 调用抽象、Prompt 模板、RAG 工具、Tool Calling 这一类能力。Spring AI Alibaba 是阿里推动的 Spring AI 扩展，一年内做出十余个主流 LLM（通义、文心、DeepSeek、Kimi、智谱、混元、豆包等）的 connector，真实企业部署案例已经出现。LangChain4j 是 LangChain 的 Java 移植，功能类似，也是框架。Java AI 推理生态（ONNX Runtime Java、DJL）解决模型推理问题，跟 Agent OS 不在同一层级。

这些都是库或框架，产物是代码、需要开发者自己搞定运行环境。Java 写的、装好就跑的 Agent OS 底座，在业界几乎为零。OpenClaw 和 Hermes 都不是 Java，Java 生态在 Agent OS 这个层级是一块明显的缺位。

## 4.2 为什么这个缺位值得补：生态完整性的角度
这里换一个角度看"Java 没人做 Agent OS"这件事。它不是从"哪种语言的人更多"这种角度，而是从生态完整性的角度。

一个健康的技术生态，应该在每一个关键层级都有自己的实现。否则这个生态在那一层就有一道断裂，断裂处就要靠跨生态的胶水去填，而胶水是脆的、是成本。

Java/Spring 生态在企业后端是极其完整的，从 Web 框架（Spring Boot）、微服务（Spring Cloud）、配置注册（Nacos）、限流熔断（Sentinel）、链路追踪（SkyWalking）、线上诊断（Arthas）到监控告警（Prometheus + Grafana），每一层都有成熟的、互相咬合的实现。企业的 ERP、CRM、CMDB、SSO、监控，大量是 Java 接口或 Java SDK。


唯独在"Agent OS"这一层，Java 生态是空的。这意味着，一个 Java 体系的企业想要一个私有可控的 Agent 底座，今天只能去用 Node.js 的 OpenClaw 或 Python 的 Hermes，然后在两套技术栈的接缝处写大量胶水，去对接自己的 Java 服务、复用自己的 Java 运维工具链、走自己的 Java 审计流程。这道接缝，正是 OpenClaw 和 Hermes 的 Java 体系用户最痛的点。

补上这个缺位，让 Java 生态在 Agent OS 这一层也有一个原生的、跟整套 Java 基础设施咬合的实现，是一件让生态变完整的事。这跟 Spring AI 当年补上"Java 的 LLM 调用层"是同一个逻辑。不是因为 Java 比别的语言强，而是因为一个完整的生态不应该在关键层级留空。

## 4.3 Java 做这件事的几个具体支点
这个缺位之所以现在值得、也能够被补上，有几个具体的技术支点。

Spring Boot 是企业后端的事实标准。一个 Java Agent OS 就是一个 Spring Boot 应用，运维体系无缝对接，IT 部门不需要学新东西，装上就能纳入现有体系。

Spring AI Alibaba 提供了现成的主流 LLM connector。十余个 LLM 的接入不需要重新造轮子，直接复用 Spring AI 的 ChatClient 抽象，让 Java Agent OS 的 LLM Provider 层站在巨人肩膀上。

JVM 成熟的运维工具链能直接复用。Nacos、Sentinel、SkyWalking、Arthas、JFR、Prometheus + Grafana，跟 Java Agent OS 是无缝的，企业现有运维体系不需要为它单独搭一套。

跟企业现有 Java 系统对接成本最低。Tool 直接调企业现有 Java 服务，不需要写跨语言的胶水代码。

严监管行业的私有部署要求让 Java 成为确定性选择。这些行业的核心系统大量是 Java，私有部署、完全审计、合规过审是硬要求，用 Java 实现可以走企业现有的 Java 审计流程，不需要为新组件单独搭一套合规通道。

GraalVM Native Image 让 Java 的启动和内存不再是短板。JDK 21 + Spring Boot 3.x + GraalVM 能做到接近 Node.js 和 Python 的启动速度和内存占用，单二进制部署可行。需要说明的是，Native Image 对 Spring AI 这类重反射、重动态代理的框架仍有编译期配置的工程代价，AOT 适配不是零成本，但这条路是通的，且在持续成熟。

## 4.4 Spring AI 的崛起是先行信号
Spring AI 在 2024 年发布，Spring AI Alibaba 一年内做出十余个主流 LLM connector，这件事说明 Java 生态在 AI 工程上的追赶速度很快。Spring AI 已经解决了底层 LLM 调用的能力，缺的就是上面那一层"Agent OS"。阿里官方在 Spring AI Alibaba Admin 项目里做企业级 Agent 应用的最佳实践，已经形成了一定的 Java AI 工程社区认知。Java AI 工程基础设施层在 2025 年解决，自然走向 Agent OS 这一层级，这是技术栈演化的逻辑顺序。

# 五、OryxOS 的定位与愿景

前面把 Agent OS 是什么、业界做到哪、Java 生态缺在哪都讲清楚了。这一章讲 OryxOS 自己想做什么、想做成什么样子。

## 5.1 OryxOS 是什么
OryxOS 是一个企业能完全掌控的、Java 原生的、私有可审计的 Agent 统一底座。

它装在企业自己的 K8s、服务器或物理机上，作为统一底座，在底座上跑各种业务 Agent（运维助手、客服助手、HR 助手、销售助手、知识管理助手等），共享一套渠道接入、模型路由、记忆系统、工具调用、安全审计能力。数据完全留在企业自己的基础设施，不锁任何云生态。

业务方在 OryxOS 上配置 Agent（prompt、模型、Tool 列表、渠道绑定），Agent 跑起来；业务方写 Tool（用 Java、Python、Shell 或任何语言，通过 MCP 协议暴露）接入 OryxOS，Agent 就能用上。业务方不需要写 Agent 后端代码，只需要写 Tool 实现和 Skill 配置。

## 5.2 OryxOS 把自己锚在哪里
这是 OryxOS 定位上最要紧的一个选择，呼应第一章和第三章的判断。

OryxOS 用"Agent OS"这个框架来理解和构建自己，因为这个框架好用，五件事讲得清楚，操作系统的类比帮助思考。但 OryxOS 不把自己锚在"Agent OS 这个概念"上，而是锚在它背后那个不会变的企业刚需上：严监管企业需要一个自己能完全掌控的 Agent 底座，私有部署、完全可审计、跟 Java 体系对齐、数据不出企业、IT 能掌控。

这个区别很重要。锚在概念上，意味着如果有一天"Agent OS"这个词被别的说法取代、这个中间层被上下层吸收，项目就失去了立足点。锚在需求上，意味着无论技术概念怎么演变，OryxOS 服务的那个需求都还在。严监管企业要私有可控、要跟 Java 对齐，这件事不会因为一个词的流行与否而改变。OryxOS 把根扎在不变的东西上。

## 5.3 OryxOS 的愿景
OryxOS 想做成的样子，可以用几个画面来描述。

让企业装一个 Agent 底座，像装一个 Spring Boot 应用一样自然。不需要学新的技术栈，不需要为它单独搭运维和审计，装上就纳入企业现有的体系。

让业务方只关心业务。渠道、模型、记忆、多租户、审计这些公共能力下沉到 OryxOS，业务方之上只写 Tool、配 Agent，上一个新 Agent 不用重复造这些轮子。

让数据和能力都留在企业。模型可以接外部 API，也可以用本地 Ollama 或 vLLM，但数据完全留在企业自己的基础设施，OryxOS 本身不收集任何企业数据。更进一步，通过 Skill 体系让高级员工的经验沉淀下来、资产化、留在企业，新员工可以继承。沉淀的知识和能力，永远属于企业。

把这几个画面收成四个词：统一、私有、易接入、可观测。

统一是企业内多个 Agent 共享一套底座；

私有是数据和部署完全在企业自己手里；

易接入是基于标准 Spring Boot 工程结构、跟现有系统和工具链直接对接、Tool 用 MCP 任何语言都能写；

可观测是标准 Prometheus 指标、结构化日志、健康检查、Web 管理台，适配企业现有监控告警。

## 5.4 OryxOS 的边界：做运行时，不做编排
OryxOS 的定位有一条清晰的边界：在分层上守住运行时这一层，不往上做编排。这里的"运行时层"是顺着第一章的区分说的，指 OryxOS 站在编排平台和应用之下、做承载 Agent 跑起来的底座，而不是说 OryxOS 缩成了一个只管单 agent 执行的纯 runtime。它仍然是完整的 Agent OS，多 Agent 管理、多租户、审计这些 OS 级能力都在，只是相对编排平台而言，它的位置在下面那一层。

OryxOS 做的事是提供 Agent 运行所需的基础设施，包括 Channel、LLM Provider、Memory、Tool 注册、Sandbox、多租户、SSO、审计、可观测性、Web 管理台。业务方在上面配置 Agent，Agent 跑起来。

OryxOS 不做可视化 workflow 编排、复杂任务分解、多 Agent 显式协作。如果业务需要复杂 workflow，可以用 Dify 之类的编排平台在 OryxOS 之上跑（Dify 作为客户端调 OryxOS 的 API）。OryxOS 跟编排平台是互补关系，不是竞争关系。这条边界让 OryxOS 专注做好"底座"这一件事。

## 5.5 OryxOS 不是什么：跟 Dify 工作流和 LangChain 框架的区别
第一章已经从概念上划过 Agent OS 跟相邻概念的边界，这里站在 OryxOS 的角度再正面讲一遍，因为这是最容易被问到、也最容易混淆的地方。把 OryxOS、Dify 这类编排平台、LangChain/Spring AI 这类框架放在一起，用三个维度看，区别就清楚了：产物是什么、谁来用、跑在哪一层。

| | 框架（LangChain、Spring AI） | 编排平台（Dify、Coze） | OryxOS |
| --- | --- | --- | --- |
| 产物 | 代码 | 一条 workflow | 配置出来的常驻 Agent |
| 比喻 | 给你材料，自己盖房子 | 给你流程，跑在运行时之上 | 盖好的房子，拎包入住 |
| 谁来用 | 开发者写代码 | 业务人员拖拽 | 业务方配置 Agent、写 Tool |
| 运行环境 | 自己搭 | 平台提供 | 自带，装好就跑 |
| 所在层 | 最底层组件 | 应用层 | 运行时层 |
| 跟 OryxOS 的关系 | 被 OryxOS 复用（做 LLM 调用） | 可跑在 OryxOS 之上 | 它自己 |
下面把每一行展开讲。

跟 Dify、Coze 这类工作流编排平台的区别。
Dify 的产物是一条 workflow，你用拖拽的方式，把 Prompt 节点、HTTP 节点、Code 节点、条件分支拼成一个有向流程，平台负责按这个流程执行。它的核心是"把一个复杂业务流程显式地编排出来"，适合一次性的、流程明确的任务，用户主要是业务人员和开发者。

OryxOS 的产物不是 workflow，是一个配置出来的、常驻运行的 Agent，它没有显式的流程图，Agent 在运行时自己根据 prompt、工具和上下文决定下一步做什么，适合做长期在线的助手。

一句话区别：Dify 编排的是"流程"，OryxOS 承载的是"常驻的 Agent"。两者不在一层，Dify 完全可以跑在 OryxOS 之上，把 OryxOS 当后端运行时来调。OryxOS 刻意不做可视化编排，正是为了不跟 Dify 抢这一层，而是做它下面那层稳的底座。

跟 LangChain、LangGraph、Spring AI、LangChain4j 这类框架的区别。
框架的产物是代码，它给开发者一组库和 SDK，让你用代码把一个 Agent 写出来，写完之后部署、运维、扩展、保活都是你自己的事，框架不管运行环境。

OryxOS 的产物是一个装好就跑的服务，渠道接入、模型路由、记忆、多租户、审计、Web 管理台这些基础设施一应俱全，业务方不写 Agent 后端代码，只配置 Agent、写 Tool。

一句话区别：框架是"给你材料让你自己盖房子"，OryxOS 是"盖好的房子，你拎包入住"。而且两者是复用关系而非竞争关系，OryxOS 内部的 LLM 调用层，正是直接复用 Spring AI / Spring AI Alibaba 这类框架来实现的。框架解决"怎么用代码调通 LLM 和工具"，OryxOS 解决"怎么把一堆 Agent 在企业里可控地常驻跑起来"，分工不同。

把三者收成一张话：框架给你代码、要你自己搭运行环境；编排平台给你流程、跑在运行时之上；OryxOS 给你运行时本身，一个让 Agent 能常驻、可治理、可审计地跑起来的底座。

OryxOS 既复用了框架（拿它当 LLM 调用的底层组件），又托住了编排平台（给它当后端运行时），自己专注守在"运行时"这一层。

## 5.6 OryxOS 在安全上怎么不重蹈 OpenClaw 的覆辙
OpenClaw 的安全问题（第二章讲过的 CVE、恶意 skill、凭证收割、第三方 skill 供应链风险）不是偶然的 bug，是结构性的。一个开放低门槛的第三方 skill 生态，加上默认宽松的权限和较弱的隔离，必然带来这些问题。OryxOS 既然定位严监管企业，安全就必须是 day one 的设计，而不是事后打补丁。具体在几件事上跟 OpenClaw 走相反的路。

Skill 和 Tool 来源受控，不做无约束的公开市场。OryxOS 不提供"任何人上传、任何 Agent 随意拉取"的开放 skill 市场。企业内的 Skill 和 Tool 要经过注册、审核、签名、版本管理，来源可追溯。社区的优质 Skill 可以导入，但要经过企业自己的审查关，而不是默认信任。

最小权限，而不是默认全开。每个 Agent、每个 Tool 拿到的权限是显式授予的最小集合，文件系统、网络、shell 的访问范围默认收紧，按需放开，而不是 OpenClaw 那种默认能力很大、出事才发现的模式。

沙箱隔离是强制的，不是可选的。Tool 在隔离环境里执行，有明确的资源和能力边界，多租户之间完全隔离，一个租户、一个 Agent 的越界不会波及其他。

凭证不落地，走企业密钥体系。API key、token、企业系统的凭证不硬编码、不明文存储，对接企业现有的密钥管理（KMS、Vault 等），凭证的使用全程可审计。

prompt injection 和数据外泄要主动防御。借鉴 Hermes 的做法，记忆写入和工具输入要经过安全扫描，检测注入和外泄模式；对 Agent 可以访问的数据范围和可以外发的目标做约束。

全链路审计是底座能力，不是事后补。谁、在什么时候、让哪个 Agent、调了什么 Tool、访问了什么数据、产生了什么结果，全程结构化留痕，可以接入企业现有的审计和 SIEM 系统。这正是 OpenClaw 和 Hermes 都缺、而严监管企业必须有的东西。

走企业现有安全审查流程。OryxOS 既然是 Java/Spring 实现、跑在企业自己基础设施上，它就能纳入企业现有的代码审计、安全扫描、合规过审流程，而不需要像 OpenClaw 那样靠 Tank OS 这种外部加固镜像才能勉强上生产。安全不是额外加的一层壳，是从架构里长出来的。

## 5.7 OryxOS 跟业界其他项目的关系
OryxOS 借鉴了开源 Agent OS 领域已经被验证的设计哲学。Agent 配置和生命周期、Channel 抽象、三层记忆、Skill 体系（SKILL.md 兼容 agentskills.io 开放标准）、Tool 调用通过 MCP 协议、单二进制部署这些设计，在 OpenClaw 和 Hermes 上都验证过能撑住真实场景。OryxOS 把这套设计在 Java 生态里重新实现，并补齐企业级 Agent OS 必需的多租户、SSO、RBAC、审计、合规、Web 管理台、深度集成这些能力。

OryxOS 跟 OpenClaw、Hermes 的关系是同类不同定位。三者都是 Agent OS，OpenClaw 偏个人、Hermes 偏个人到小团队，OryxOS 直接定位严监管企业场景。Skill 体系上通过 SKILL.md 跟两者兼容，社区的优质 Skill 经过企业审查后理论上可以导入 OryxOS。这里不需要论证谁取代谁。开源世界里同类项目共存是常态，OryxOS 只是把"企业完全可控 + Java 原生"这个特定取向做到位。

OryxOS 跟 Dify、Coze 这类编排平台的关系是互补。Dify 做可视化 workflow 编排，OryxOS 做运行时基础设施，企业可以用 Dify 给业务人员搭快速应用、用 OryxOS 跑核心助手，两者甚至可以组合（Dify 作应用层，OryxOS 作基础设施层）。

OryxOS 跟 Spring AI、Spring AI Alibaba、LangChain4j 这些 Java AI 框架的关系是复用。OryxOS 的 LLM Provider 抽象直接基于 Spring AI Alibaba 的主流 LLM connector，不重复造轮子。OryxOS 是 Agent OS，Spring AI 是底层 LLM 调用能力，两者在分工上没有冲突。

# 六、未来方向：从单机到分布式

需要先把当前的边界讲清楚：OryxOS 当前版本做的是单机私有部署。


一个 OryxOS 实例，装在企业自己的一台服务器或一个容器里，跑起一组 Agent，服务一个部门或一个场景。这是刻意的选择。绝大多数企业落地 Agent 底座，都是从"先在一个场景把它跑通、跑稳"开始的，单机形态最简单、最易接入、最容易纳入现有运维和审计，也最符合"装一个 Spring Boot 应用一样自然"的初衷。先把单机这件事做扎实，是当前阶段的全部重心。

但架构上要为未来留出方向。当一个企业从"一个部门试点"走向"服务全公司"，单机会撞到三件事：

扛不住量（单实例的并发和吞吐有上限）

扛不住故障（单点挂了整个 Agent 服务就停）

扛不住规模化治理（几十个部门、上百个 Agent、大量租户共用时，单机的资源和隔离不够用）。

分布式部署，就是为了应对这三件事而预留的演进方向。这一章讲的是设想，不是当前已实现的能力。

讲分布式之前要先把一个容易混淆的地方说清楚：这里其实有两层完全不同的"分布式"。一层是底座本身的分布式部署，也就是同一个 OryxOS 跑多个副本，解决扛量和高可用，这是运维层面的事，6.1 到 6.4 讲的都是它。另一层更远、也更有意思，是Agent 之间的分布式协作，多个 Agent 跨节点、跨部门甚至跨组织地互相发现、委托、协同，这里的"节点"是 Agent 而不是服务器。后者是一个真正前沿的方向，放在 6.5 单独讲。

## 6.1 演进的核心原则：实例无状态，状态外置
从单机走向集群，最关键的一步是把状态从实例里剥离出来。单机形态下，session、memory、审计日志、Agent 配置这些状态可以放在本地；要做分布式，这些状态必须外置到独立的存储层，让 OryxOS 实例本身变成无状态的。任何一个实例挂了或扩容了，不影响整体，因为状态不在实例里。

具体设想是：会话和短期上下文外置到 Redis 一类的内存存储，长期记忆和 Skill 库外置到 PostgreSQL（向量检索可以用 pgvector 或独立的向量库），审计日志和大文件外置到对象存储，Agent 配置和租户信息外置到配置中心和数据库。实例只负责"接消息、调模型、跑工具、读写外置状态"，自己不持有任何不可重建的状态。这是分布式的地基，没有这一步，后面的高可用和水平扩展都无从谈起。

## 6.2 多实例下要解决的几件事
实例无状态之后，多实例并行会带来几个必须解决的协调问题，这里讲清楚思路。

渠道接入的消息不能重复消费。多个实例同时连着同一个飞书或企业微信渠道时，一条用户消息只能被一个实例处理，不能被多个实例重复响应。设想是通过一个统一的消息分发层（或基于消息队列的消费组）来保证每条消息恰好被一个实例消费，Gateway 层做无状态的接入、把消息投递到队列，由实例去抢占式或分片式消费。

Agent 的调度和归属。多实例下，一个 Agent 的请求可以落到任意实例上（因为状态外置了，任意实例都能处理），但有些有状态的长任务、定时任务（cron）需要明确的归属，避免重复执行。设想是引入一个轻量的调度协调层，定时任务和长驻任务通过分布式锁或租约机制保证只有一个实例在跑。

多租户在分布式下的隔离。单机的多租户靠进程内的逻辑隔离，分布式下租户的数据分散在外置存储里，隔离要做到存储层。每个租户的 session、memory、审计在存储层就是隔离的（独立的 key 前缀、独立的 schema 或独立的库），加上请求链路上全程带租户标识，保证一个租户的请求在任何实例上都只能访问自己的数据。

## 6.3 高可用与水平扩展
有了无状态实例和外置状态，高可用和水平扩展就自然了。多实例部署在 K8s 上，前面挂负载均衡，单个实例故障时流量自动切到其他实例，状态不丢（因为在外置存储里）。负载上来时，水平扩容实例数即可，因为实例无状态，扩容不需要数据迁移。外置的存储层（Redis、PG、对象存储）各自按成熟的方案做自己的高可用，这些都是企业已经熟悉的中间件。

## 6.4 为什么 Java 生态做分布式是顺手的
这一章其实是"Java 原生"价值的又一次体现。分布式系统要解决的服务注册发现、配置管理、限流熔断、分布式追踪、负载均衡这些问题，Java 生态有一整套企业级验证过的成熟方案，而且大概率是企业现有体系里已经在用的：服务注册和配置中心用 Nacos，限流熔断用 Sentinel，网关用 Spring Cloud Gateway，分布式追踪用 SkyWalking，监控告警用 Prometheus + Grafana。OryxOS 做分布式，不需要自己造这些轮子，直接站在 Spring Cloud 这套成熟的分布式基础设施上，跟企业现有的微服务治理体系用同一套工具、同一套运维方法。

这正是 Node.js 的 OpenClaw 和 Python 的 Hermes 做企业级分布式时要费很大劲、而 Java 顺手就能做的地方。分布式、高可用、服务治理，本来就是 Java/Spring 生态最成熟、企业最信任的强项。把 Agent OS 放在这套地基上，分布式不是从零搭，是复用。

需要再强调一次：以上是面向规模化的演进设想，当前版本聚焦把单机做扎实。把分布式的方向想清楚、在架构上预留好，是为了让单机版本的设计不堵死未来的路，而不是现在就把复杂度全压上来。

## 6.5 更远的愿景：分布式 Agent 协作
前面 6.1 到 6.4 讲的是"一个 OryxOS 跑多份副本"，那是把同一个底座做大。还有一个更远、也更有意思的方向，是把"分布式"用在 Agent 身上，而不是用在底座的副本上。

设想这样一个场景：一个企业里有几十上百个 Agent，分散在不同部门、不同机器，甚至延伸到合作伙伴的组织里。运维部门有运维 Agent，财务有财务 Agent，法务有法务 Agent。一个真实的业务往往横跨多个部门，比如一笔大额采购，要运维 Agent 确认资源、财务 Agent 核算预算、法务 Agent 审合同。

今天这些 Agent 是一个个孤岛，各跑各的。而分布式 Agent 协作要解决的，就是让它们能跨节点地互相发现、把任务可靠地委托给对方、共享必要的上下文、协同完成一件横跨多方的事。这里的"节点"是 Agent，整个图景更像一个 Agent 版的分布式系统。

这件事现在还很早。业界关于 multi-agent 的探索，大多还停留在单机进程内的几个 Agent 互相 delegate，真正跨节点、跨组织的 Agent 协作，基础设施几乎还是空白。这跟分布式系统的早期很像。先有单机多进程，后来才有跨机器的服务发现、RPC、消息中间件把它们连起来。

Agent 世界现在大概就在"单机多进程"这个阶段，而把多个节点的 Agent 可靠连起来的那层中间件，还没真正立起来。所以这是一个方向性的、卡位性的愿景，不是当下的需求。市场连"单个 Agent 稳定上生产"都还没普遍做到，谈跨节点 Agent 协作的真实需求为时尚早。把它写进来，是因为它是 OryxOS 这条线自然会生长到的远方，值得现在就看清。

而这个远方有一个关键的技术地基，恰好不在 OryxOS 自己身上，而在 Agent 之间的通信底座上。多个 Agent 要跨节点可靠协作，最难的不是单个 Agent 跑得好不好，而是它们之间怎么注册发现、怎么可靠地异步通信、怎么在不可靠的网络上保证消息不丢不重、怎么传递跨节点的上下文，这些正是消息和通信基础设施要解决的问题。

沿着这个思路，OryxOS 的远期图景可以这样描述：OryxOS 是单个节点上的 Agent 运行时，而连接多个 OryxOS 节点、让上面的 Agent 能跨节点可靠协作的，是一层专门的 Agent 通信底座。 单节点的运行时和跨节点的通信底座是两件事，分开演进，最后合起来，才构成完整的"分布式 Agent OS"。

需要清楚地标注边界：6.5 是这份调研里最远的一笔，是愿景层面的设想，跟当前要做的单机版本隔着好几步。写下它，是为了让方向感完整。OryxOS 当前扎扎实实做单机，中期沿 6.1 到 6.4 演进到底座的分布式部署，远期才走向分布式 Agent 协作。每一步都建立在前一步做扎实的基础上，不跳步。

三个阶段并排看，路标就清楚了。

| 阶段 | 形态 | 解决什么 | 状态 |
| --- | --- | --- | --- |
| 当前 | 单机私有部署 | 一个场景先跑通、跑稳，易接入、易审计 | 当前重心 |
| 中期 | 底座分布式部署 | 扛量、高可用、规模化治理（多副本无状态） | 架构预留 |
| 远期 | 分布式 Agent 协作 | 跨节点 Agent 互相发现、委托、协同 | 愿景 |
# 附录 A：关键术语
Agent OS：Agent Operating System，运行和管理 AI Agent 的基础设施层，装在用户（或企业）自己的机器上，提供多渠道、多 LLM 路由、记忆、工具、隔离等完整运行环境。

Agent：具象的智能体，有具体的工种、人格设定和任务范围。一个 Agent 由 prompt、Skills、Tools、Memory 几部分组合而成，在 Agent OS 上配置出来，不是写代码写出来的。

Skill：可复用的 Agent 能力模板，用 SKILL.md 文件格式描述，兼容 agentskills.io 开放标准。OpenClaw 和 Hermes 都用这个格式。

Tool：Agent 可以调用的外部能力，通常通过 MCP 协议暴露。业务方用任何语言写 Tool，注册到 Agent OS 供 Agent 使用。

Channel：Agent 对外接入的渠道，包括企业微信、飞书、钉钉、Slack、邮件、HTTP API、Web 等。

LLM Provider：大模型的提供方抽象，实现统一接口让 Agent 不感知具体调的是哪家模型。

MCP：Model Context Protocol，Anthropic 2024 年 11 月提出的 LLM 与外部工具或数据源连接的开放协议，目前是 Agent 生态的事实标准。

Agent 编排平台：visual workflow builder，用拖拽方式组合节点搭 AI 应用。Dify、Coze、扣子是代表。跟 Agent OS 是不同层级，Agent OS 是底层运行时，编排平台是上层应用工具。

Agent 框架：给开发者用代码写 Agent 的库或 SDK。LangChain、Spring AI、LangChain4j 是代表。框架的产物是代码，运行需要开发者自己搞定。

多租户：一个 Agent OS 实例同时服务多个组织、部门、项目，每个租户独立 memory、session、data，完全隔离。

Gateway：Agent OS 里负责连接外部渠道（IM、邮件等）和路由消息的核心组件。OpenClaw 和 Hermes 都用这个术语。

Spring AI：Spring 官方推出的 Java AI 应用开发框架，提供 LLM 调用抽象、Prompt 模板、RAG 工具等能力。

Spring AI Alibaba：阿里推动的 Spring AI 扩展，提供十余个主流 LLM 的 connector。

# 附录 B：主要参考资料
OpenClaw 相关：OpenClaw GitHub（github.com/openclaw/openclaw）、官网 openclaw.ai、ClawHub skills 注册中心、VoltAgent awesome-openclaw-skills、BytePioneer-AI openclaw-china、中文社区 clawd.org.cn、社区教程与出版书籍、Tank OS（Sally O’Malley / Red Hat）、Lyzr OpenClaw 企业分析。

Hermes Agent 相关：Hermes Agent GitHub（github.com/NousResearch/hermes-agent）、官方文档、awesome-hermes-usecases、飞书/企业微信部署 issue、PTG 企业级管理服务、腾讯云与阿里云部署指南、社区中文系列文档、OpenRouter 日推理量排行。

Java 生态相关：Spring AI（docs.spring.io/spring-ai）、Spring AI Alibaba（java2ai.com）、LangChain4j。

业界标准：Model Context Protocol（modelcontextprotocol.io）、agentskills.io 开放标准。

业界研究报告：Anthropic 2026 State of AI Agents、Gartner 相关 Agent 治理与 AIOps 报告、企业级 AI Agent 应用实践研究报告等。

安全披露：CVE-2026-25253（OpenClaw 1-click RCE）、Trend Micro 恶意 skill 报告、关联社区数据库泄漏分析、Cisco AI 安全测试。

注：文中涉及的 star 数、用例比例、社区规模等数据来自公开报道与项目页面，会随时间变化，具体以原始来源的最新数据为准；部分单一来源的精确数字已做保守表述。