package io.oryxos.provider;

import io.oryxos.core.profile.Profile;

/**
 * ProviderService —— 统一管理所有 LLM Provider，对 ReAct 循环屏蔽不同 LLM 厂商差异。
 *
 * <p>职责边界很窄：挑对模型、发起一次调用、把结果拿回来、落审计。
 * 循环怎么转、工具怎么执行、上下文怎么拼都不归它管。</p>
 *
 * <p>内部维护 provider name → ChatModel 的<b>显式映射</b>（不靠类型扫描），
 * 并关闭 Spring AI 的自动工具执行。</p>
 */
public interface ProviderService {

    /**
     * 调用 LLM。
     *
     * @param sessionId 会话 ID（审计记录按 session 关联，所以必须传）
     * @param profile   当前 Agent 的 Profile（含 provider/model/temperature）
     * @param prompt    本次调用的输入
     * @return LLM 响应（文本 + 可能的工具调用请求）
     */
    LlmResponse chat(String sessionId, Profile profile, Prompt prompt);
}
