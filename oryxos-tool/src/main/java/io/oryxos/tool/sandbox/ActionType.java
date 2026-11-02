package io.oryxos.tool.sandbox;

/**
 * 沙箱要裁决的动作类型。
 *
 * <p>枚举值是"意图"的分类，不是某一档实现的分类：新增隔离档（容器、microVM）时
 * 不需要新增类型，只需要新增 {@link Sandbox} 的实现。</p>
 */
public enum ActionType {

    /** 访问文件系统上的一个路径（读、写、列目录都算）。 */
    FILE_ACCESS,

    /** 执行一条命令。 */
    SHELL_EXEC,

    /** 发起一次出站 HTTP 请求。 */
    HTTP_REQUEST
}
