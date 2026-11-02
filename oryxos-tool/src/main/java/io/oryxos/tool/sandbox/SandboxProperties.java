package io.oryxos.tool.sandbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 白名单策略的配置绑定。
 *
 * <p>对应 {@code application.yaml} 的 {@code oryxos.sandbox} 段：</p>
 *
 * <pre>
 * oryxos:
 *   sandbox:
 *     enabled: true                     # 默认 true；置为 false 即完全关闭校验
 *     allowed-paths: [${user.dir}]
 *     allowed-commands: [echo, git]
 *     allowed-domains: ["*.example.com"]
 * </pre>
 *
 * <p><b>空清单即全拒</b>：任一清单为空时该维度一律拒绝，不是一律放行。
 * 这是刻意的默认方向——安全边界应当收着，放开必须是显式动作。
 * 代价是新建工作区不配白名单时工具全部不可用，因此拒绝原因必须可读。</p>
 *
 * <p><b>关于 {@link #enabled}</b>：这是唯一的"关闭安全边界"入口，因此</p>
 * <ul>
 *   <li><b>默认 true</b>：宁可让开发时多配几行，也不能让生产环境因为漏配而敞着；</li>
 *   <li>关闭后三个维度<b>全部放行</b>，且这两个清单的内容不再有任何约束力；</li>
 *   <li>关闭时启动日志会打出醒目的告警——这条配置最怕的就是"忘了改回来"。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "oryxos.sandbox")
public class SandboxProperties {

    /**
     * 是否启用白名单校验。
     *
     * <p>默认 {@code true}。置为 {@code false} 时文件读写、命令执行、HTTP 请求一律放行，
     * <b>仅</b>适用于本地开发与调试。</p>
     */
    private boolean enabled = true;

    private List<String> allowedPaths = new ArrayList<>();

    private List<String> allowedCommands = new ArrayList<>();

    private List<String> allowedDomains = new ArrayList<>();

    /**
     * 目录 Agent 可用的解释器清单（按可执行文件名比较，忽略大小写与 {@code .exe}）。
     *
     * <p>只对"带 agentDir 的 Agent"生效：它们的 {@code shell} 只能跑
     * {@code <解释器> <本目录 scripts/xxx>}。legacy Profile 仍走 {@link #allowedCommands}。</p>
     */
    private List<String> allowedInterpreters = new ArrayList<>(WhitelistSandbox.DEFAULT_INTERPRETERS);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedPaths() {
        return allowedPaths;
    }

    public void setAllowedPaths(List<String> allowedPaths) {
        this.allowedPaths = allowedPaths;
    }

    public List<String> getAllowedCommands() {
        return allowedCommands;
    }

    public void setAllowedCommands(List<String> allowedCommands) {
        this.allowedCommands = allowedCommands;
    }

    public List<String> getAllowedDomains() {
        return allowedDomains;
    }

    public void setAllowedDomains(List<String> allowedDomains) {
        this.allowedDomains = allowedDomains;
    }

    public List<String> getAllowedInterpreters() {
        return allowedInterpreters;
    }

    public void setAllowedInterpreters(List<String> allowedInterpreters) {
        this.allowedInterpreters = allowedInterpreters;
    }
}
