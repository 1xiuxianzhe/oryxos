package io.oryxos.core.profile;

/**
 * 一条**加载失败**的 Agent 目录。
 *
 * <p>为什么要单独记：目录 Agent 的失败只写日志时，"我丢了个目录它却没出现"是这个机制最常见、
 * 也最难自查的问题。列出跳过项，调用方才能直接说清"这个目录为什么没生效"。</p>
 *
 * <p>一个目录一条（多个问题合并成一条 reason）：一个目录算一个单位，拆成多条只会让页面重复。</p>
 *
 * @param dirName 目录名（也是它**本应**成为的 Agent 名）
 * @param reason  跳过原因（与日志里的原因一致）
 */
public record SkippedAgent(String dirName, String reason) {
}
