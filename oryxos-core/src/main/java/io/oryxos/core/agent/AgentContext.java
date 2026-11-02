package io.oryxos.core.agent;

import io.oryxos.core.profile.Profile;

/**
 * 当前 Agent 配置上下文。
 *
 * <p>{@code OryxTool.execute} 的签名不带 Profile，但有些工具在执行时需要知道"当前是哪个 Agent"
 * （例如通知类工具要读当前 Profile 的通知渠道）。让 {@code AgentService} 在入口处放入、
 * 出口处清理，工具想用就取——虚拟线程下每个请求独占一个线程，天然不串。</p>
 *
 * <p><b>生命周期严格等于"一次处理"</b>：处理开始 {@link #set}，处理结束（含异常）必须
 * {@link #clear}，否则复用线程的下一个请求会拿到别人的 Profile。</p>
 *
 * <p>除了 Profile，这里还携带本次处理的 {@code runId}（请求级审计 ID，见 {@link #currentRunId()}）。
 * 存储层的审计实现据此把每次 LLM 调用 / 工具调用挂到对应的请求记录上，
 * 从而避免为了一个"当前处理"的横切标识去改动各处审计接口的签名。</p>
 */
public final class AgentContext {

    private static final ThreadLocal<Profile> CURRENT = new ThreadLocal<>();

    private static final ThreadLocal<String> RUN_ID = new ThreadLocal<>();

    private AgentContext() {
    }

    /** 兼容入口：只设置 Profile，不带 runId（如仅做上下文注入的场景）。 */
    public static void set(Profile profile) {
        set(profile, null);
    }

    /** 处理开始时同时放入 Profile 与本次处理的 runId。 */
    public static void set(Profile profile, String runId) {
        CURRENT.set(profile);
        RUN_ID.set(runId);
    }

    /** 未设置时返回 null。 */
    public static Profile current() {
        return CURRENT.get();
    }

    /**
     * 当前处理的 runId；不在处理过程中时返回 null。
     *
     * <p>存储层审计实现用它把 {@code llm_calls} / {@code tool_invocations} 的明细
     * 关联到 {@code agent_runs} 的请求级记录上。</p>
     */
    public static String currentRunId() {
        return RUN_ID.get();
    }

    public static void clear() {
        CURRENT.remove();
        RUN_ID.remove();
    }
}
