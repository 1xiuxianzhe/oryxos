package io.oryxos.core.schedule;

/**
 * 一条**注册失败**的定时任务配置。
 *
 * <p>为什么要把它暴露出来：注册失败只记 ERROR 日志，而"我配了一个任务但它从来没跑过"
 * 是这类系统最常见、也最难自查的问题——日志翻不到就等于不存在。列出跳过项，
 * 前端才能直接说清"这条为什么没生效"。</p>
 *
 * @param profileName 所属 Profile
 * @param id          配置里的 id（可能为空，因为"缺 id"本身就是一种跳过原因）
 * @param cron        配置里的 cron 原文
 * @param reason      跳过原因（与日志里的原因一致）
 */
public record SkippedTask(String profileName, String id, String cron, String reason) {
}
