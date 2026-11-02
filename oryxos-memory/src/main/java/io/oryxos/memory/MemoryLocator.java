package io.oryxos.memory;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.profile.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MemoryLocator —— 决定"这次读写的是谁的记忆"。
 *
 * <p>规则只有一条：<b>当前处理上下文里的 Profile 带 {@code agentDir} 就用它自己的记忆，
 * 否则回退到工作区共享记忆</b>。</p>
 *
 * <p>为什么不在 {@code MemoryService} 接口上加 {@code Profile} 参数：那会让
 * {@code PromptBuilder}、记忆工具、Web/CLI 的调用点全部改签名，而它们其实都处在
 * "当前正在为哪个 Agent 干活"这一个上下文里。把定位收在实现内部，接口保持稳定。</p>
 *
 * <p><b>按路径缓存实例</b>不是性能优化，而是正确性要求：{@link LongTermMemory} 的写锁是
 * 实例级的，若每次调用都新建实例，两个并发 run 会各自持一把锁去写同一个文件。</p>
 */
public class MemoryLocator {

    private static final Logger logger = LoggerFactory.getLogger(MemoryLocator.class);

    /** 工作区共享记忆（legacy Profile 与"不在处理上下文"时的回退位置）。 */
    private final Path workspaceMemoryFile;

    /** 路径 → 实例；同路径必须复用同一实例以保住写锁语义。 */
    private final Map<Path, LongTermMemory> instances = new ConcurrentHashMap<>();

    public MemoryLocator(Path workspaceRoot) {
        this.workspaceMemoryFile = MemoryFiles.resolve(workspaceRoot).toAbsolutePath().normalize();
    }

    /**
     * 当前处理上下文应使用的记忆文件。
     *
     * <p>目录 Agent → {@code <agentDir>/memory/MEMORY.md}；其余情况（legacy Profile、Web/CLI
     * 直接调用、没有上下文）→ 工作区记忆。后者不是"降级"，而是既有语义的保留。</p>
     */
    public Path currentFile() {
        Path agentDir = currentAgentDir();
        if (agentDir == null) {
            return workspaceMemoryFile;
        }
        return MemoryFiles.resolve(agentDir).toAbsolutePath().normalize();
    }

    /** 当前上下文对应的记忆实例（同一路径始终返回同一实例）。 */
    public LongTermMemory current() {
        return forFile(currentFile());
    }

    /** 指定路径的记忆实例。 */
    public LongTermMemory forFile(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        return instances.computeIfAbsent(normalized, LongTermMemory::new);
    }

    /** 工作区共享记忆文件（诊断用）。 */
    public Path workspaceMemoryFile() {
        return workspaceMemoryFile;
    }

    private static Path currentAgentDir() {
        try {
            Profile profile = AgentContext.current();
            return profile == null ? null : profile.getAgentDir();
        } catch (RuntimeException e) {
            logger.debug("[Memory] 读取当前 Agent 上下文失败，回退到工作区记忆: {}", e.getMessage());
            return null;
        }
    }
}
