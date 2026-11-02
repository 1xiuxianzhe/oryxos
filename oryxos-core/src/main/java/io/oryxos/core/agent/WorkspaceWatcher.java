package io.oryxos.core.agent;

import io.oryxos.core.profile.AgentLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * WorkspaceWatcher —— 让"往目录里丢一个 Agent"立即生效，不用重启。
 *
 * <p>这是 29 节"一个目录 = 一个 Agent"缺的最后一块：此前目录只在启动时被扫描一次，
 * 于是"改目录 → 重启"成了必选项。本节把它变成"改目录 → 300ms 后生效"。</p>
 *
 * <p><b>四个必须守住的判断：</b></p>
 * <ol>
 *   <li><b>{@link WatchService} 不递归</b>：注册 {@code agents/} 只能拿到"子目录被创建/删除"，
 *       子目录里 {@code AGENT.md} 的修改必须再对每个子目录注册一次。少注册一层，
 *       表现就是"新建目录能被发现，改了内容却永远不生效"。</li>
 *   <li><b>必须去抖</b>：编辑器保存一次文件往往产生 2~4 个事件，拷目录更是几十个。
 *       不去抖就会对一个正在写的文件连续解析，中间态必然解析失败。</li>
 *   <li><b>半写不注销</b>：AGENT.md 被写成非法内容时，正确的行为是"保留旧注册 + 记原因"，
 *       而不是把这个 Agent 从运行时抹掉——用户手滑一次不该让线上 Agent 消失。</li>
 *   <li><b>启动不重扫</b>：启动扫描归 {@code AgentLoader}。这里只在启动时补注册"监听键"，
 *       不回调 {@link AgentChangeListener}，否则同一个 Agent 会被注册两次（双份 cron）。</li>
 * </ol>
 *
 * <p><b>线程模型</b>：一个守护线程阻塞在 {@code watchService.take()}，一个单线程调度器做去抖。
 * 两者都不做业务，业务（解析 / 注册 / 注销）全在 {@link AgentChangeListener} 里，异常一律吞在监听侧。</p>
 */
public class WorkspaceWatcher implements InitializingBean, DisposableBean {

    /** 目录变更回调：由 {@code AgentLifecycleService} 实现。 */
    public interface AgentChangeListener {

        /** 目录出现或 {@code AGENT.md} 变了（半写/非法内容由实现方决定"保留旧注册"）。 */
        void onChanged(Path agentDir);

        /** 目录消失了。 */
        void onRemoved(String name);
    }

    /** 默认去抖窗口：编辑器保存一次通常在 50ms 内产生全部事件。 */
    public static final long DEFAULT_DEBOUNCE_MILLIS = 300L;

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceWatcher.class);

    private final Path agentsDir;
    private final AgentChangeListener listener;
    private final long debounceMillis;

    /** 待处理目录 → 最近一次事件的时间戳（纳秒）。只有时间戳仍是最新的那个任务才真正处理。 */
    private final Map<Path, Long> pending = new ConcurrentHashMap<>();

    /** WatchKey → 被监听的目录。 */
    private final Map<WatchKey, Path> watchedKeys = new ConcurrentHashMap<>();

    /** 被监听的目录 → WatchKey（避免对同一目录重复注册出多个 key）。 */
    private final Map<Path, WatchKey> watchedPaths = new ConcurrentHashMap<>();

    private final ScheduledExecutorService debounceExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "oryxos-watcher-debounce");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean running;
    private WatchService watchService;
    private Thread watcherThread;

    public WorkspaceWatcher(Path agentsDir, AgentChangeListener listener) {
        this(agentsDir, listener, DEFAULT_DEBOUNCE_MILLIS);
    }

    public WorkspaceWatcher(Path agentsDir, AgentChangeListener listener, long debounceMillis) {
        this.agentsDir = agentsDir.toAbsolutePath().normalize();
        this.listener = listener;
        this.debounceMillis = Math.max(0L, debounceMillis);
    }

    @Override
    public void afterPropertiesSet() {
        start();
    }

    /** 启动监听；任何失败都只记日志（监听是增强能力，不能因为它让整个实例起不来）。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            Files.createDirectories(agentsDir);
            watchService = FileSystems.getDefault().newWatchService();
            watch(agentsDir);
            watchExistingSubdirectories();
        } catch (IOException e) {
            logger.error("[Watcher] 无法监听 Agent 目录（目录变更不会自动生效，需重启）: {}", agentsDir, e);
            closeQuietly();
            return;
        }

        running = true;
        watcherThread = new Thread(this::loop, "oryxos-workspace-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
        logger.info("[Watcher] 已监听 Agent 目录: {}（去抖 {}ms，事件只处理 AGENT.md）",
                agentsDir, debounceMillis);
    }

    /**
     * 只补注册监听键，<b>不</b>回调监听器。
     *
     * <p>启动扫描已经由 {@code AgentLoader} 做过一次；这里再回调一遍就是同一个 Agent 注册两次——
     * 而定时任务对同一 key 是覆盖句柄而非取消旧句柄，结果是两条 cron 一起跑。</p>
     */
    private void watchExistingSubdirectories() throws IOException {
        try (Stream<Path> stream = Files.list(agentsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                watch(dir);
            }
        }
    }

    // ------------------------------------------------------------------
    // 监听循环
    // ------------------------------------------------------------------

    private void loop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }

            Path dir = watchedKeys.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    logger.warn("[Watcher] 事件队列溢出，可能漏掉部分变更: {}", dir);
                    continue;
                }
                if (!(event.context() instanceof Path changed)) {
                    continue;
                }
                try {
                    handleEvent(dir, dir.resolve(changed));
                } catch (RuntimeException e) {
                    logger.error("[Watcher] 处理文件事件失败（继续监听其它事件）: {}", dir, e);
                }
            }

            if (!key.reset()) {
                // 目录已被删除（或不可访问）：注销这个 key，并让业务侧判定"这个 Agent 还在不在"
                watchedKeys.remove(key);
                watchedPaths.remove(dir);
                if (isDirectChildOfAgentsDir(dir)) {
                    onEvent(dir);
                }
            }
        }
        logger.info("[Watcher] 监听线程退出");
    }

    /** 把一次文件事件翻译成"哪个 Agent 目录需要重新检查"。 */
    private void handleEvent(Path watchedDir, Path child) {
        String fileName = child.getFileName() == null ? "" : child.getFileName().toString();
        if (isTempFile(fileName)) {
            return;
        }

        if (watchedDir.equals(agentsDir)) {
            // agents/ 下的事件：新目录出现 / 目录被删 / 落在这一层的普通文件
            if (Files.exists(child)) {
                if (!Files.isDirectory(child)) {
                    // agents/ 根下的普通文件（README、临时文件等）与 Agent 无关，不惊动业务侧
                    return;
                }
                watchQuietly(child);
            }
            // 目录已消失时也要进一次判定：有的平台删目录只报父目录事件，不单独报子项
            onEvent(child);
            return;
        }

        // 子目录里：只有 AGENT.md 值得重注册；scripts/ skills/ REFERENCE.md 变更不需要
        if (!AgentLoader.MAIN_FILE.equals(fileName)) {
            return;
        }
        onEvent(watchedDir);
    }

    /** 记下"这个目录刚变过"，去抖窗口结束后若没有更新的事件再处理。 */
    private void onEvent(Path dir) {
        Path key = dir.toAbsolutePath().normalize();
        long stamp = System.nanoTime();
        pending.put(key, stamp);
        debounceExecutor.schedule(() -> flush(key, stamp), debounceMillis, TimeUnit.MILLISECONDS);
    }

    private void flush(Path dir, long stamp) {
        Long current = pending.get(dir);
        if (current == null || current != stamp) {
            // 窗口内又有新事件：那个任务会负责处理，这次直接让位
            return;
        }
        pending.remove(dir);
        process(dir);
    }

    /** 判定一个目录的当前状态并回调监听器；任何异常都不外抛。 */
    private void process(Path dir) {
        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
        try {
            if (!Files.isDirectory(dir)) {
                listener.onRemoved(name);
                return;
            }
            if (!Files.isRegularFile(dir.resolve(AgentLoader.MAIN_FILE))) {
                // 目录刚建、AGENT.md 还没落盘，或用户删掉了主文件：等下一次事件，不注销
                logger.debug("[Watcher] 目录 [{}] 暂无可解析的 {}，等待后续事件", name, AgentLoader.MAIN_FILE);
                return;
            }
            listener.onChanged(dir);
        } catch (RuntimeException e) {
            logger.error("[Watcher] 处理目录变更失败（旧注册保留）: dir={}, reason={}", dir, e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 测试入口（不依赖 OS 时序）
    // ------------------------------------------------------------------

    /** 模拟"这个目录发生了变化"：走与真实事件完全相同的去抖与判定逻辑。 */
    void handleChange(Path agentDir) {
        onEvent(agentDir);
    }

    /** 跳过去抖等待，立即处理一次（测试用；业务判定与真实路径共用 {@link #process(Path)}）。 */
    void processNow(Path agentDir) {
        process(agentDir.toAbsolutePath().normalize());
    }

    /** 去抖窗口长度（测试用于等待）。 */
    public long debounceMillis() {
        return debounceMillis;
    }

    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private void watch(Path dir) throws IOException {
        Path normalized = dir.toAbsolutePath().normalize();
        if (watchedPaths.containsKey(normalized)) {
            return;
        }
        WatchKey key = normalized.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        watchedKeys.put(key, normalized);
        watchedPaths.put(normalized, key);
    }

    private void watchQuietly(Path dir) {
        try {
            watch(dir);
        } catch (IOException e) {
            logger.warn("[Watcher] 无法监听子目录（该目录内的修改需重启才生效）: {}", dir, e);
        }
    }

    private boolean isDirectChildOfAgentsDir(Path dir) {
        Path parent = dir.getParent();
        return parent != null && parent.equals(agentsDir);
    }

    /** 原子写的临时文件形如 {@code AGENT.md.tmp-123456}：它们本身不是变更。 */
    private static boolean isTempFile(String fileName) {
        return fileName.contains(".tmp-") || fileName.endsWith("~");
    }

    private void closeQuietly() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                logger.debug("[Watcher] 关闭 WatchService 失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public synchronized void destroy() {
        running = false;
        closeQuietly();
        debounceExecutor.shutdownNow();
        if (watcherThread != null) {
            watcherThread.interrupt();
            try {
                watcherThread.join(TimeUnit.SECONDS.toMillis(2));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            watcherThread = null;
        }
        watchedKeys.clear();
        watchedPaths.clear();
        pending.clear();
        logger.info("[Watcher] 已停止监听: {}", agentsDir);
    }
}
