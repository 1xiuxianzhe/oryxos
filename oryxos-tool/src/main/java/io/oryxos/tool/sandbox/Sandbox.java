package io.oryxos.tool.sandbox;

/**
 * 安全边界：在受控环境里执行一个动作。
 *
 * <p>这是整个沙箱体系唯一的"墙"：上层（Tool）只表达"我要在受控环境里做这件事"，
 * 不感知当前是哪一档隔离。核心阶段只有 {@link WhitelistSandbox} 一个实现；
 * 未来加容器、microVM 实现时，<b>这个接口签名不应发生变化</b>。</p>
 *
 * <p><b>为什么只有一个方法</b>：一旦把"检查路径""检查命令"之类的方法提到接口上，
 * 接口就被第一档实现带偏了——容器不是"检查路径"，而是"提供一个隔离的文件系统"。
 * 用最重的那一档（microVM）反向校验：它能否只新增一个实现类就套进来？能，才算墙立住。</p>
 *
 * <p><b>为什么用异常而不是布尔返回值</b>：返回值会被调用方忘记判断，
 * 而"忘记判断"在安全边界上等于没有边界。</p>
 */
public interface Sandbox {

    /**
     * 裁决一个动作：放行则正常返回，拒绝则抛出 {@link SandboxViolationException}。
     *
     * @param action 待裁决的动作
     * @throws SandboxViolationException 该动作不被当前策略允许
     */
    void enforce(SandboxAction action);
}
