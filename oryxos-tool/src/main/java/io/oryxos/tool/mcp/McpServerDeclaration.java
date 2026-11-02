package io.oryxos.tool.mcp;

import java.util.List;
import java.util.Map;

/**
 * 外部工具服务的一条声明——描述"怎么连上它"。
 *
 * <p>连接信息的唯一真相来源是工作区的声明文件；Agent 配置里只是按 {@code name} 引用，
 * 从而避免"同一个服务在三个地方各写一份连接参数"。</p>
 *
 * <p>两种连接方式共用一条声明：</p>
 * <ul>
 *   <li>{@code stdio}：{@code command} 必填，{@code args} / {@code env} 可选；</li>
 *   <li>{@code sse}：{@code url} 必填。</li>
 * </ul>
 *
 * @param name      服务标识（必填，用于告警定位与 Agent 引用）
 * @param transport 连接方式，取值为 {@link #TRANSPORT_STDIO} 或 {@link #TRANSPORT_SSE}
 * @param command   本地子进程的启动命令（仅 stdio）
 * @param args      本地子进程的启动参数（仅 stdio）
 * @param env       本地子进程的环境变量（仅 stdio；值可能来自环境变量占位）
 * @param url       远程服务地址（仅 sse）
 */
public record McpServerDeclaration(String name,
                                   String transport,
                                   String command,
                                   List<String> args,
                                   Map<String, String> env,
                                   String url) {

    /** 本地子进程连接方式。 */
    public static final String TRANSPORT_STDIO = "stdio";

    /** 远程连接方式。 */
    public static final String TRANSPORT_SSE = "sse";

    public static McpServerDeclaration stdio(String name, String command, List<String> args, Map<String, String> env) {
        return new McpServerDeclaration(name, TRANSPORT_STDIO, command, args, env, null);
    }

    public static McpServerDeclaration sse(String name, String url) {
        return new McpServerDeclaration(name, TRANSPORT_SSE, null, List.of(), Map.of(), url);
    }

    public boolean isStdio() {
        return TRANSPORT_STDIO.equalsIgnoreCase(transport);
    }

    public boolean isSse() {
        return TRANSPORT_SSE.equalsIgnoreCase(transport);
    }

    public List<String> argsOrEmpty() {
        return args == null ? List.of() : args;
    }

    public Map<String, String> envOrEmpty() {
        return env == null ? Map.of() : env;
    }
}
