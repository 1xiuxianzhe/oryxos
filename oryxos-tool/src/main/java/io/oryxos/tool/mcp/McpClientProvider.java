package io.oryxos.tool.mcp;

import io.modelcontextprotocol.client.McpSyncClient;

/**
 * 建立与外部工具服务的连接。
 *
 * <p><b>为什么抽成端口</b>：验证"某个服务失联时只告警、不阻断启动"这件事，需要测试能精确控制
 * "一个连得上、一个连不上"。若连接逻辑写死在服务类里，就只能起真实进程或真实网络来测，
 * 既慢又不稳。抽出端口后，单测可以注入替身，而适配与注册流程仍被真实覆盖——
 * 只有"如何建立连接"这一层被替换。</p>
 *
 * <p>这也是把 SDK 收敛在一处的边界：将来若底层实现需要更换，改动范围限定在实现类与适配器。</p>
 */
public interface McpClientProvider {

    /**
     * 建立并初始化连接。
     *
     * @param declaration 服务声明
     * @return 已初始化、可立即调用的客户端
     * @throws RuntimeException 连接失败（未启动、拒绝、超时、握手失败等）
     */
    McpSyncClient connect(McpServerDeclaration declaration);
}
