package io.oryxos.cli;

/**
 * 命令运行模式。
 *
 * <p>轻命令（init/status/profile 等）直接走文件操作，不启动 Spring 上下文；
 * 重命令（chat/serve/gateway）需要模型与引擎能力，才启动 Spring 上下文。</p>
 */
public enum CommandMode {
    /** 不启动 Spring 上下文。 */
    LIGHT,
    /** 需要完整的 Agent 运行时。 */
    HEAVY
}
