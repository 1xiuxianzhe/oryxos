package io.oryxos.core.schedule;

import java.time.Instant;

/**
 * 一条已注册定时任务的只读视图（给管理平台用）。
 *
 * <p>刻意只暴露"配置是什么、下一次什么时候跑、此刻在不在跑"这类事实，
 * 不暴露 {@code Profile} 对象本身——查询接口不该把可变的领域对象递到视图层。</p>
 *
 * @param key          任务键 {@code profileName#id}
 * @param cron         配置里的 cron 表达式（6 段：秒 分 时 日 月 周）
 * @param timezone     实际生效的时区（未配置时是系统时区，这里给出解析后的值而不是 null）
 * @param message      触发时交给 Agent 的消息
 * @param running      此刻是否正在执行（占着防重叠锁）
 * @param runningForMs 已执行时长（毫秒）；未在运行时为 {@code null}
 * @param nextRunAt    下一次触发时刻；计算失败时为 {@code null}
 */
public record ScheduledTaskInfo(String key,
                                String profileName,
                                String id,
                                String cron,
                                String timezone,
                                String message,
                                boolean running,
                                Long runningForMs,
                                Instant nextRunAt) {
}
