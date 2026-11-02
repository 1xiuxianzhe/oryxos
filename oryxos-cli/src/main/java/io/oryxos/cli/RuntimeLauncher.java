package io.oryxos.cli;

import io.oryxos.channel.cli.CliChannel;

import java.util.List;

/** 完整运行时的 CLI 接线端口，由 Boot/Spring 提供实现。 */
public interface RuntimeLauncher {
    CliChannel chatChannel();

    default void serve(int port) {
        throw new UnsupportedOperationException("HTTP 运行时未配置");
    }

    default void gateway() {
        throw new UnsupportedOperationException("Gateway 运行时未配置");
    }

    /**
     * 注册表中的工具名快照。
     *
     * <p>工具清单的真相来源是注册表，而注册表只在运行时里存在，因此清单命令必须经这里取值，
     * 不得另造一份硬编码清单。</p>
     */
    default List<String> registeredTools() {
        throw new UnsupportedOperationException("Tool 注册表需要运行时");
    }
}
