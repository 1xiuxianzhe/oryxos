package io.oryxos.memory;

import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 记忆统一门面的默认实现：长期记忆委托给 {@link LongTermMemory}（文件式 + 关键词检索）。
 *
 * <p>这是"接口墙"的实现侧。将来把长期记忆换成向量检索，只需替换本类内部的协作对象，
 * 上层（{@code PromptBuilder}、工具）一行都不用改。</p>
 *
 * <p>会话记忆不在这里：当前对话的往来记录由 {@code Session}/{@code SessionManager} 承担、
 * 由 {@code PromptBuilder} 独立一段拼装，门面不重复输出（避免同一内容注入两次）。</p>
 *
 * <p><b>按 Agent 隔离</b>：路径由 {@link MemoryLocator} 按当前处理上下文决定；
 * 两种构造方式分别服务"按上下文解析"（运行时）与"固定文件"（单元测试、单 Agent 场景）。</p>
 */
public class DefaultMemoryService implements MemoryService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultMemoryService.class);

    /** 按上下文解析的定位器；与 {@link #fixedMemory} 二者必有其一。 */
    private final MemoryLocator locator;

    /** 固定文件（测试与无上下文场景）。 */
    private final LongTermMemory fixedMemory;

    public DefaultMemoryService(LongTermMemory longTermMemory) {
        this.fixedMemory = Objects.requireNonNull(longTermMemory, "longTermMemory");
        this.locator = null;
    }

    public DefaultMemoryService(MemoryLocator locator) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.fixedMemory = null;
    }

    /** 本次调用真正使用的记忆实例。 */
    private LongTermMemory memory() {
        return locator != null ? locator.current() : fixedMemory;
    }

    /**
     * 组装"注入视角"的长期记忆（核心区全量 + 归档区按阈值截断），供 PromptBuilder 使用。
     *
     * <p>读的是<b>当前处理上下文所属 Agent</b> 的那份记忆（见 {@link MemoryLocator}）；
     * 任何读取异常都降级为空串——记忆读不到只是"少了背景"，不该让整轮对话失败。</p>
     */
    @Override
    public String buildContext(Session session) {
        try {
            String memory = memory().load();
            return memory == null ? "" : memory;
        } catch (RuntimeException e) {
            // 读取路径的兜底：底层已尽量降级，这里再保一道——记忆段为空远好过整轮对话失败
            logger.warn("[Memory] 组装长期记忆失败，本轮回退为空记忆: {}", memory().file(), e);
            return "";
        }
    }

    /**
     * 只读快照：与管理平台/CLI 共享的读口径，不写文件、不改状态。
     *
     * <p>注意：它同样按"当前上下文"取文件。在 HTTP 请求线程里调用时没有处理上下文，
     * 会回退到工作区共享记忆——这是既有语义，不是缺陷；要按 Agent 查记忆需等下一节的接口。</p>
     */
    @Override
    public String snapshot() {
        try {
            String memory = memory().load();
            return memory == null ? "" : memory;
        } catch (RuntimeException e) {
            logger.warn("[Memory] 读取记忆快照失败，按空记忆处理: {}", memory().file(), e);
            return "";
        }
    }

    /**
     * 指定 Agent 的记忆快照（管理台 Agent 详情页）。
     *
     * <p>走 {@link MemoryLocator#forFile} 而不是新建 {@code LongTermMemory}：同一路径必须复用
     * 同一实例，否则管理台的读与运行时的写会各持一把锁去碰同一个文件。</p>
     */
    @Override
    public String snapshot(java.nio.file.Path agentDir) {
        if (agentDir == null) {
            return snapshot();
        }
        LongTermMemory memory = locator != null
                ? locator.forFile(MemoryFiles.resolve(agentDir))
                : fixedMemory;
        try {
            String content = memory.load();
            return content == null ? "" : content;
        } catch (RuntimeException e) {
            logger.warn("[Memory] 读取 Agent 记忆快照失败，按空记忆处理: agentDir={}", agentDir, e);
            return "";
        }
    }

    /**
     * 写一条记忆。写入目标由当前 Agent 决定（目录 Agent 写自己的文件）。
     *
     * <p>异常不在这里吞掉：写入失败要让调用方（{@code save_memory} 工具）拿到并转成
     * "可重试的失败结果"，而不是假装记住了——"以为记了其实没记"是最难发现的静默错误。</p>
     */
    @Override
    public void remember(String content, MemoryScope scope) {
        memory().append(content, scope);
    }

    /**
     * 按关键词检索归档区（核心区不需要检索，它每轮都被注入）。
     *
     * <p>检索异常按"未命中"处理：检索只是一种便利，失败时让模型拿不到历史，
     * 好过让整轮对话崩掉。</p>
     */
    @Override
    public List<String> recall(String keyword) {
        try {
            return memory().recallByKeyword(keyword);
        } catch (RuntimeException e) {
            logger.warn("[Memory] 关键词检索失败，按未命中处理: {}", memory().file(), e);
            return List.of();
        }
    }
}
