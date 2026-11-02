package io.oryxos.tool.sandbox;

import java.nio.file.Path;

/**
 * 一次待裁决的动作：在受控环境里"做什么"，落到一个目标上。
 *
 * <p>字段刻意极少：{@code type} 决定 {@code target} 怎么解释（路径 / 命令串 / URL），
 * 实现类自行裁决。这里不出现"白名单""容器""镜像"这类词——接口表达意图，不表达实现。</p>
 *
 * <p><b>{@code agentDir}</b>：当动作来自"目录 Agent"时携带它的目录，为 {@code null} 表示
 * 来自 legacy Profile。带上它有两个用处：把文件访问收窄到该 Agent 自己的目录；把命令执行
 * 收窄到该 Agent 的 {@code scripts/}。放在动作里而不是让沙箱去读 ThreadLocal，是为了让沙箱
 * 保持**无状态、可单测**——给它什么就裁决什么。</p>
 *
 * @param type     动作类型
 * @param target   动作目标（路径 / 命令串 / URL）
 * @param agentDir 当前 Agent 目录；legacy 场景为 {@code null}
 */
public record SandboxAction(ActionType type, String target, Path agentDir) {

    /** 兼容构造：不带 Agent 目录（legacy Profile 的全部调用点）。 */
    public SandboxAction(ActionType type, String target) {
        this(type, target, null);
    }

    /** 是否来自目录 Agent。 */
    public boolean hasAgentDir() {
        return agentDir != null;
    }
}
