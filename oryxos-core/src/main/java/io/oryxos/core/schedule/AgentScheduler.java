package io.oryxos.core.schedule;

import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * AgentScheduler —— 第三种触发源：到点自己拼一条消息，交给 {@link AgentService}。
 *
 * <p>与 CLI / Web Service 的本质区别只是"谁发起"：那两个是人推，这里是钟推。
 * 一旦进入 {@code agentService.process(session, message)}，后面怎么想、怎么调模型、怎么动工具，
 * 与人工触发完全一样——这也是这个类刻意保持"薄"的原因。</p>
 *
 * <p><b>它只做三件事</b>：注册（{@link #registerAll()}）、到点取锁、调用 AgentService。
 * 消息内容归 Profile / SKILL.md，处理过程归 ReActLoop，消息怎么送出去归 notify，都不在这里。</p>
 *
 * <p><b>四个已知的坑与解法</b>：</p>
 * <ol>
 *   <li><b>配置从哪来</b>——{@code schedules} 在 Profile 里声明，运行期用
 *       {@link TaskScheduler#schedule(Runnable, org.springframework.scheduling.Trigger)} 动态注册；
 *       不用 {@code @Scheduled}（cron 写死在注解里，改一次要重新编译）。</li>
 *   <li><b>重叠执行</b>——每个任务一把进程内 {@link ReentrantLock}，{@code tryLock} 失败直接跳过本次触发。
 *       核心阶段单实例，够用；多实例归属协调属扩展阶段。</li>
 *   <li><b>失败隔离</b>——单次执行的异常只记 ERROR，不冒到调度线程；锁在 {@code finally} 释放。</li>
 *   <li><b>时区</b>——{@link CronTrigger} 显式带 {@link ZoneId}；未配置时用系统时区，
 *       但启动日志会把实际值写出来，不让"约定俗成"替用户做主。</li>
 * </ol>
 *
 * <p><b>会话身份</b>固定为 {@code ("scheduler", "scheduler", profileName)}：同一 Profile 的历次触发
 * 复用同一个 Session，历史靠 {@code settings.max_history_turns} 截断兜底。</p>
 *
 * <p><b>审计</b>：本类不写任何审计——触发走的就是 {@link AgentService} 的既有链路
 * （{@code agent_runs} 与下游 {@code llm_calls} / {@code tool_invocations}），
 * 为定时单开一套账等于承认"两种触发源走两条链路"，与定位矛盾。</p>
 */
public class AgentScheduler implements InitializingBean, DisposableBean {

    /** 定时触发的会话渠道名。 */
    public static final String CHANNEL = "scheduler";

    /** 定时触发的会话用户标识（没有人类用户，用固定值换"同一 Profile 复用同一会话"）。 */
    public static final String USER_ID = "scheduler";

    private static final Logger logger = LoggerFactory.getLogger(AgentScheduler.class);

    /** 日志里消息内容的截断长度。 */
    private static final int MESSAGE_PREVIEW_LIMIT = 40;

    private final TaskScheduler taskScheduler;
    private final ProfileRegistry profileRegistry;
    private final SessionManager sessionManager;
    private final AgentService agentService;

    /**
     * 防重叠锁，键 = {@code profileName#id}。
     *
     * <p>刻意包含 profileName：{@code id} 只是 Profile 内的局部标识，两个 Agent 都写
     * {@code id: daily} 是完全正常的配置，只按 id 取锁会让它们互相阻塞。</p>
     *
     * <p>只增不减：任务数有限，删条目会破坏"同一任务永远同一把锁"的语义。</p>
     */
    private final Map<String, Lock> taskLocks = new ConcurrentHashMap<>();

    /** 已注册任务键；用于启动日志、重复检测与测试断言。 */
    private final Set<String> registeredTasks = ConcurrentHashMap.newKeySet();

    /**
     * 已注册的调度句柄：键 = {@code profileName#id}。
     *
     * <p>刻意用 Map 而不是 List：注销一个 Agent 时要能按 Profile 精确取回属于它的全部句柄并取消，
     * 否则会出现"注册表里已经删了、定时还在跑"——这种不一致比删不掉更糟。</p>
     */
    private final Map<String, ScheduledFuture<?>> handles = new ConcurrentHashMap<>();

    /** 注册成功任务的完整信息（配置 + Trigger + 实际时区），供只读视图计算下一次触发时刻。 */
    private final Map<String, TaskRegistration> registrations = new ConcurrentHashMap<>();

    /** 正在执行的任务：键 → 开始时间戳。 */
    private final Map<String, Long> runningSince = new ConcurrentHashMap<>();

    /** 注册阶段被跳过的配置及原因：配了但没生效，必须让人看得见。 */
    private final List<SkippedTask> skipped = new CopyOnWriteArrayList<>();

    /** 注册成功时留下的一份信息快照。 */
    private record TaskRegistration(Profile profile, Profile.ScheduleConfig config,
                                    Trigger trigger, ZoneId zone) {
    }

    public AgentScheduler(TaskScheduler taskScheduler,
                          ProfileRegistry profileRegistry,
                          SessionManager sessionManager,
                          AgentService agentService) {
        this.taskScheduler = taskScheduler;
        this.profileRegistry = profileRegistry;
        this.sessionManager = sessionManager;
        this.agentService = agentService;
    }

    @Override
    public void afterPropertiesSet() {
        registerAll();
    }

    /**
     * 扫描全部 Profile 的 {@code schedules}，逐条注册。
     *
     * <p><b>单条非法只跳过该条</b>：定时任务是外围能力，一条坏 cron 不该让整个 Agent OS 起不来
     * （与 ProfileLoader"坏文件跳过"的既有取向一致）。代价是必须靠日志发现，
     * 因此每条跳过都带 profile、条目序号与原始值。</p>
     *
     * @return 实际注册成功的条数
     */
    public int registerAll() {
        Map<String, Profile> profiles = profileRegistry.getAllProfiles();
        int registered = 0;
        int skipped = 0;

        for (Profile profile : profiles.values()) {
            int[] result = registerProfileDetailed(profile);
            registered += result[0];
            skipped += result[1];
        }

        logger.info("[Scheduler] 定时任务注册完成：成功 {} 条，跳过 {} 条（扫描 Profile {} 个）",
                registered, skipped, profiles.size());
        return registered;
    }

    /**
     * 注册**单个** Profile 的 {@code schedules}。
     *
     * <p>启动扫描与运行时登记共用这一段：这也是"两条路径行为一致"（spec FR-037）在调度侧的落点。</p>
     *
     * @return 实际注册成功的任务数
     */
    public int registerProfile(Profile profile) {
        return registerProfileDetailed(profile)[0];
    }

    /** @return {@code [注册成功数, 跳过数]} */
    private int[] registerProfileDetailed(Profile profile) {
        if (profile == null) {
            logger.error("[Scheduler] 注册定时任务时 Profile 为空，已忽略");
            return new int[]{0, 0};
        }
        List<Profile.ScheduleConfig> schedules = profile.getSchedules();
        if (schedules == null || schedules.isEmpty()) {
            return new int[]{0, 0};
        }

        int registered = 0;
        int skipped = 0;
        for (int index = 0; index < schedules.size(); index++) {
            if (registerOne(profile, schedules.get(index), index)) {
                registered++;
            } else {
                skipped++;
            }
        }
        return new int[]{registered, skipped};
    }

    /**
     * 注销一个 Profile 的**全部**定时任务。
     *
     * <p>与 {@code ProfileRegistry.remove} 是配套动作：只删注册表不取消调度，等于让一个
     * "已经不存在"的 Agent 继续到点跑。两者必须由调用方一起做（本节不提供合并入口，
     * 因为合并会掩盖"忘了其中一个"这种缺陷）。</p>
     *
     * @return 被取消的任务数
     */
    public int unregisterProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            logger.warn("[Scheduler] 注销定时任务时 Profile 名为空，已忽略");
            return 0;
        }
        String prefix = profileName + "#";
        int cancelled = 0;
        for (Map.Entry<String, ScheduledFuture<?>> entry : handles.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }
            if (entry.getValue() != null) {
                // 计数按"已下达取消"算，而不是 cancel() 的返回值：任务可能已经跑完，
                // 返回 false 不代表它还活着，但句柄仍然要从表里清掉
                entry.getValue().cancel(false);
            }
            cancelled++;
            handles.remove(key);
            registeredTasks.remove(key);
            registrations.remove(key);
        }
        if (cancelled == 0) {
            logger.debug("[Scheduler] Profile [{}] 没有可注销的定时任务", profileName);
        } else {
            logger.info("[Scheduler] 已注销 Profile [{}] 的 {} 个定时任务", profileName, cancelled);
        }
        return cancelled;
    }

    /** 某个 Profile 当前已注册的任务键（只读快照；供诊断与测试）。 */
    public Set<String> registeredTasksOf(String profileName) {
        String prefix = (profileName == null ? "" : profileName) + "#";
        return registeredTasks.stream().filter(key -> key.startsWith(prefix)).collect(Collectors.toUnmodifiableSet());
    }

    /** 注册单条；返回 false 表示该条被跳过（原因已记日志，并留在 {@link #skippedTasks()} 里）。 */
    private boolean registerOne(Profile profile, Profile.ScheduleConfig config, int index) {
        String where = "profile=" + profile.getName() + ", 序号=" + index;

        if (config == null) {
            logger.error("[Scheduler] 定时任务配置为空，已跳过：{}", where);
            return skip(profile, null, "定时任务配置为空");
        }

        String id = trimToNull(config.getId());
        if (id == null) {
            logger.error("[Scheduler] 定时任务缺少 id，已跳过：{}（id 用于防重叠锁与日志定位，必填）", where);
            return skip(profile, config, "缺少 id（id 用于防重叠锁与日志定位，必填）");
        }

        String key = taskKey(profile.getName(), id);
        if (registeredTasks.contains(key)) {
            logger.error("[Scheduler] 定时任务 id 重复，已跳过后者：key={}, {}", key, where);
            return skip(profile, config, "id 重复，已跳过后者: " + key);
        }

        String cron = trimToNull(config.getCron());
        if (cron == null) {
            logger.error("[Scheduler] 定时任务缺少 cron，已跳过：key={}, {}", key, where);
            return skip(profile, config, "缺少 cron");
        }

        String message = resolveMessage(config);
        if (message == null) {
            logger.error("[Scheduler] 定时任务缺少 message（且无 task 可回退），已跳过：key={}, {}", key, where);
            return skip(profile, config, "缺少 message（且无 task 可回退）");
        }

        ZoneId zone;
        try {
            zone = resolveZone(config.getTimezone(), key);
        } catch (RuntimeException e) {
            logger.error("[Scheduler] 定时任务时区非法，已跳过：key={}, timezone={}, 原因={}",
                    key, config.getTimezone(), e.getMessage());
            return skip(profile, config, "时区非法: " + config.getTimezone());
        }

        CronTrigger trigger;
        try {
            trigger = new CronTrigger(cron, zone);
        } catch (RuntimeException e) {
            logger.error("[Scheduler] 定时任务 cron 非法，已跳过：key={}, cron={}, 原因={}",
                    key, cron, e.getMessage());
            return skip(profile, config, "cron 非法: " + e.getMessage());
        }

        ScheduledFuture<?> future;
        try {
            future = taskScheduler.schedule(() -> runOnce(profile, config), trigger);
        } catch (RuntimeException e) {
            logger.error("[Scheduler] 定时任务注册失败，已跳过：key={}, cron={}, 原因={}",
                    key, cron, e.getMessage());
            return skip(profile, config, "注册失败: " + e.getMessage());
        }
        if (future == null) {
            logger.error("[Scheduler] 调度器未接受该任务（可能已关闭），已跳过：key={}, cron={}", key, cron);
            return skip(profile, config, "调度器未接受该任务（可能已关闭）");
        }

        handles.put(key, future);
        registeredTasks.add(key);
        registrations.put(key, new TaskRegistration(profile, config, trigger, zone));
        logger.info("[Scheduler] 已注册定时任务: key={}, cron={}, zone={}, message={}",
                key, cron, zone, preview(message));
        return true;
    }

    /** 记一条跳过项并返回 false，让各校验分支保持单行表达。 */
    private boolean skip(Profile profile, Profile.ScheduleConfig config, String reason) {
        skipped.add(new SkippedTask(
                profile == null ? null : profile.getName(),
                config == null ? null : trimToNull(config.getId()),
                config == null ? null : trimToNull(config.getCron()),
                reason));
        return false;
    }

    /**
     * 执行一次定时任务：取锁 → 取会话 → 交给 AgentService → 放锁。
     *
     * <p>公开是为了让测试与将来的"手动触发"复用同一条逻辑——本轮不提供手动触发 API。</p>
     *
     * <p>顺序上的两个不变式：校验/取会话都在锁内，避免同一条任务并发取到两个会话；
     * 放锁必须在 {@code finally}，否则这个任务会永远卡住。</p>
     */
    public void runOnce(Profile profile, Profile.ScheduleConfig config) {
        if (profile == null || config == null) {
            logger.error("[Scheduler] 定时任务执行参数为空，已忽略");
            return;
        }
        String id = trimToNull(config.getId());
        if (id == null) {
            logger.error("[Scheduler] 定时任务缺少 id，无法取锁，已忽略：profile={}", profile.getName());
            return;
        }

        String key = taskKey(profile.getName(), id);
        Lock lock = taskLocks.computeIfAbsent(key, ignored -> new ReentrantLock());
        if (!lock.tryLock()) {
            // 不排队、不等待：上一次还没跑完，这一次直接丢弃
            logger.info("[Scheduler] 上一次尚未结束，跳过本次触发: key={}", key);
            return;
        }

        long beganAt = System.currentTimeMillis();
        runningSince.put(key, beganAt);
        try {
            String message = resolveMessage(config);
            if (message == null) {
                logger.error("[Scheduler] 定时任务缺少 message（且无 task 可回退），本次不执行：key={}", key);
                return;
            }

            Session session = sessionManager.getOrCreate(CHANNEL, USER_ID, profile.getName());
            logger.info("[Scheduler] 定时任务触发: key={}, sessionId={}, message={}",
                    key, session.getSessionId(), preview(message));

            // 与 CLI / Web 完全相同的入口：后面怎么想、怎么调工具，调度层不再参与
            agentService.process(session, message);

            logger.info("[Scheduler] 定时任务完成: key={}, 耗时={}ms", key, System.currentTimeMillis() - beganAt);
        } catch (Exception e) {
            // 失败隔离：不外抛、不重试，下一次触发照常
            logger.error("[Scheduler] 定时任务失败（不重试，调度器继续运行）: key={}, 耗时={}ms, error={}",
                    key, System.currentTimeMillis() - beganAt, e.getMessage(), e);
        } finally {
            runningSince.remove(key);
            lock.unlock();
        }
    }

    /** 取（或创建）某任务专属的锁；键为 {@code profileName#id}。包内可见，供测试模拟"上一次还占着锁"。 */
    Lock lockFor(String profileName, String id) {
        return taskLocks.computeIfAbsent(taskKey(profileName, id), ignored -> new ReentrantLock());
    }

    /** 已注册任务的键集合（不可变快照）。 */
    public Set<String> registeredTasks() {
        return Set.copyOf(registeredTasks);
    }

    /**
     * 当前已注册任务的只读快照：配置、实际时区、下一次触发时刻、是否正在执行。
     *
     * <p>给管理平台用。按 {@code profileName + id} 排序，保证同一份配置每次返回顺序一致——
     * 否则前端表格每次刷新都会跳。</p>
     */
    public List<ScheduledTaskInfo> snapshot() {
        long now = System.currentTimeMillis();
        List<ScheduledTaskInfo> tasks = new ArrayList<>();
        for (Map.Entry<String, TaskRegistration> entry : registrations.entrySet()) {
            TaskRegistration registration = entry.getValue();
            Long since = runningSince.get(entry.getKey());
            tasks.add(new ScheduledTaskInfo(
                    entry.getKey(),
                    registration.profile().getName(),
                    trimToNull(registration.config().getId()),
                    trimToNull(registration.config().getCron()),
                    registration.zone().getId(),
                    resolveMessage(registration.config()),
                    since != null,
                    since == null ? null : Math.max(0L, now - since),
                    nextExecution(registration.trigger())));
        }
        tasks.sort(Comparator.comparing(ScheduledTaskInfo::profileName)
                .thenComparing(ScheduledTaskInfo::id, Comparator.nullsLast(Comparator.naturalOrder())));
        return tasks;
    }

    /** 注册阶段被跳过的配置（含原因）；没有跳过时返回空列表。 */
    public List<SkippedTask> skippedTasks() {
        return List.copyOf(skipped);
    }

    /** 向 Trigger 要下一次执行时刻；算不出来返回 null，不影响其它任务。 */
    private static Instant nextExecution(Trigger trigger) {
        try {
            return trigger.nextExecution(new TriggerContext() {
                @Override
                public Instant lastScheduledExecution() {
                    return null;
                }

                @Override
                public Instant lastActualExecution() {
                    return null;
                }

                @Override
                public Instant lastCompletion() {
                    return null;
                }

                @Override
                public Clock getClock() {
                    return Clock.systemDefaultZone();
                }
            });
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 任务键：{@code profileName#id}。 */
    static String taskKey(String profileName, String id) {
        return (profileName == null ? "" : profileName) + "#" + (id == null ? "" : id);
    }

    @Override
    public void destroy() {
        int cancelled = 0;
        for (ScheduledFuture<?> future : handles.values()) {
            if (future != null && future.cancel(false)) {
                cancelled++;
            }
        }
        handles.clear();
        registeredTasks.clear();
        registrations.clear();
        runningSince.clear();
        logger.info("[Scheduler] 已取消 {} 个定时任务（随应用关闭）", cancelled);
    }

    /** 消息取 {@code message}，为空时回退兼容字段 {@code task}；都为空返回 null。 */
    private static String resolveMessage(Profile.ScheduleConfig config) {
        String message = trimToNull(config.getMessage());
        return message != null ? message : trimToNull(config.getTask());
    }

    private static ZoneId resolveZone(String timezone, String key) {
        String value = trimToNull(timezone);
        if (value == null) {
            ZoneId systemZone = ZoneId.systemDefault();
            logger.info("[Scheduler] 任务 {} 未显式配置 timezone，使用系统时区 {}", key, systemZone);
            return systemZone;
        }
        return ZoneId.of(value);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String preview(String message) {
        return message.length() <= MESSAGE_PREVIEW_LIMIT
                ? message
                : message.substring(0, MESSAGE_PREVIEW_LIMIT) + "...";
    }
}
