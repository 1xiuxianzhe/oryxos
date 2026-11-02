package io.oryxos.core.profile;

/**
 * AgentDefinitionException —— 一个 Agent 目录 / 一段 AGENT.md 内容无法被底座理解。
 *
 * <p>与 {@code FrontmatterException} 的分工：后者是 {@link AgentLoader} 内部的解析细节，
 * 前者是<b>对外可展示</b>的失败原因——消息里必须能指到具体问题（缺哪个文件、哪个字段非法、
 * 引用了未声明的 provider），因为它的第一批消费者是 Web 端点的 400 响应与管理台的红字提示。</p>
 *
 * <p>刻意继承 {@link RuntimeException}：解析失败是"配置问题"而非"系统故障"，
 * 调用方（生命周期服务、监听器）要么转成 400，要么记日志后跳过，都不该被迫到处 try-catch 受检异常。</p>
 */
public class AgentDefinitionException extends RuntimeException {

    public AgentDefinitionException(String message) {
        super(message);
    }

    public AgentDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
