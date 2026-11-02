package io.oryxos.core.memory;

import io.oryxos.core.session.Session;

import java.util.List;

/**
 * MemoryService —— 记忆统一门面，引擎访问记忆的唯一入口（宪法原则 V）。
 *
 * <p>这是一道"接口墙"：墙之上（{@code PromptBuilder}、{@code ReActLoop}）只认这三个方法；
 * 墙之下是明文文件，还是将来换成向量检索，上层不感知，因此升级存储时上层一行不用改。</p>
 *
 * <p><b>职责边界</b>：门面只负责<b>长期记忆</b>（跨会话）。当前对话的往来记录属于会话记忆，
 * 由 {@code Session}/{@code SessionManager} 承担，并由 {@code PromptBuilder} 的独立一段拼装——
 * 门面不重复输出会话历史，否则同一内容会在上下文里出现两次。</p>
 *
 * <p><b>失败姿态</b>：读取路径（文件缺失、区块缺失、IO 失败）一律降级为空结果，<b>不抛异常</b>——
 * 记忆只是上下文的一部分，不该拥有中断对话的否决权。写入路径的失败向上抛，由工具层转成
 * 失败的 {@code ToolResult}，让 Agent 知道"没记住"。</p>
 */
public interface MemoryService {

    /**
     * 取回用于组装 Prompt 的长期记忆：核心区完整 + 归档区（必要时截断）。
     *
     * <p>每次调用都重新读取，不做缓存——这样 {@code save_memory} 之后下一轮立刻可见。</p>
     *
     * @param session 当前会话；核心阶段按工作区共享记忆（不按 Agent/用户隔离），
     *                该参数为扩展阶段的"按 Agent 隔离记忆"预留
     * @return 记忆文本；无记忆时为空串，绝不返回 {@code null}
     */
    String buildContext(Session session);

    /**
     * 只读快照：当前"注入视角"的长期记忆，供管理平台与诊断展示。
     *
     * <p>与 {@link #buildContext(Session)} 同一读口径（核心区完整 + 归档区按阈值截断），
     * 不写文件、不改状态。管理平台需要"看到记忆"，但读路径不能绕开门面去碰文件——
     * 一旦开了这个口子，后来者会照着抄，文件格式就会从实现细节变成事实协议。</p>
     *
     * <p>失败姿态与读路径一致：任何读取异常都降级为空串。</p>
     *
     * @return 记忆文本；无记忆时为空串，绝不返回 {@code null}
     */
    String snapshot();

    /**
     * 按 Agent 查只读快照：{@code <agentDir>/memory/MEMORY.md} 的"注入视角"。
     *
     * <p>给管理台的 Agent 详情页用。加这个方法而不是让 Web 层自己去拼路径，理由与
     * {@link #snapshot()} 相同：记忆文件的位置与格式是实现细节，一旦被调用方知道，
     * 它就变成了协议，将来换存储要改的地方就从一处变成很多处。</p>
     *
     * <p>失败姿态一致：读不到（目录不存在、文件缺失、IO 失败）一律返回空串。</p>
     *
     * @param agentDir Agent 目录（{@code Profile#getAgentDir()}）；为 {@code null} 时回退到 {@link #snapshot()}
     * @return 记忆文本；无记忆时为空串，绝不返回 {@code null}
     */
    String snapshot(java.nio.file.Path agentDir);

    /**
     * 记住一条。
     *
     * @param content 记忆内容（空白内容必须被拒绝）
     * @param scope   写入哪个区块；{@code null} 时按 {@link MemoryScope#ARCHIVAL} 处理
     * @throws IllegalArgumentException 内容为空
     * @throws IllegalStateException    写入失败（如路径不可写）
     */
    void remember(String content, MemoryScope scope);

    /**
     * 在归档区按关键词检索（大小写不敏感）。
     *
     * <p>核心记忆永远在场，不需要检索，因此不在检索范围内。</p>
     *
     * @param keyword 关键词；为空白时返回空列表（<b>不得</b>退化成"返回全部归档记忆"）
     * @return 命中条目；未命中为空列表
     */
    List<String> recall(String keyword);
}
