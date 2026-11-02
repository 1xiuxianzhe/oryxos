package io.oryxos.web.dto;

import java.util.List;

/**
 * 工作区浏览的传输模型。
 *
 * <p>路径一律是<b>相对工作区</b>的写法（{@code agents/demo/AGENT.md}）：既避免把部署目录
 * 暴露给前端，也让"同一个请求在不同机器上都能定位"成立。</p>
 */
public final class WorkspaceDtos {

    private WorkspaceDtos() {
    }

    /**
     * 目录树节点。
     *
     * @param path 相对工作区的路径
     * @param type {@code dir} 或 {@code file}
     * @param size 文件字节数；目录为 0
     */
    public record Node(String name, String path, String type, long size, List<Node> children) {
    }

    /**
     * 树视图。
     *
     * @param maxDepth   本次返回的最大深度（显式声明，避免调用方以为拿到了整棵树）
     * @param maxEntries 单目录条目上限（超出即截断）
     */
    public record TreeView(String root, int maxDepth, int maxEntries, List<Node> nodes) {
    }

    /**
     * 文件内容。
     *
     * @param editable 是否允许在管理台里保存（二进制与超大文件为 {@code false}）
     */
    public record FileView(String path, String content, long size, boolean editable) {
    }

    /** 写文件请求。 */
    public record WriteFile(String path, String content) {
    }

    /** 写文件结果。 */
    public record WriteResult(String path, long size) {
    }
}
