package io.oryxos.tool.builtin;

import io.oryxos.core.agent.AgentContext;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;
import io.oryxos.tool.notify.NotifyChannelAdapter;
import io.oryxos.tool.notify.NotifyConfigException;
import io.oryxos.tool.notify.NotifySendException;
import io.oryxos.tool.notify.NotifyTarget;
import io.oryxos.tool.notify.NotifyTargetResolver;
import io.oryxos.tool.sandbox.ActionType;
import io.oryxos.tool.sandbox.Sandbox;
import io.oryxos.tool.sandbox.SandboxAction;
import io.oryxos.tool.sandbox.SandboxViolationException;

import java.util.Map;

/**
 * NotifyTool —— 内置的 {@code notify} 工具，让 Agent 能把结果主动送出去。
 *
 * <p>固定执行顺序（不变式，**校验永远早于发送**）：</p>
 * <ol>
 *   <li>从 {@link AgentContext} 取当前 Profile；</li>
 *   <li>解析通知目标（配置问题在此暴露）；</li>
 *   <li>沙箱校验（目标 URL 的域名维度）；</li>
 *   <li>发送。</li>
 * </ol>
 * <p>第 2、3 步任一失败时，第 4 步调用次数为 0。</p>
 *
 * <p><b>为什么校验不写在 execute 首行</b>：目标 URL 由当前 Profile 的通知渠道配置决定，
 * 首行拿不到。首行只能校验空值——那是最坏的一种"看起来安全"。此处的语义仍是
 * "校验严格早于副作用"，只是位置后移了一位。</p>
 *
 * <p>{@link Sandbox} 是<b>强制依赖</b>：不再有"未装配校验器就放行"的状态。</p>
 */
public class NotifyTool implements OryxTool {

    /** 工具名（全局唯一，供 LLM Function Calling 引用）。 */
    public static final String NAME = "notify";

    private static final String PARAM_CONTENT = "content";
    private static final String PARAM_CHANNEL = "channel";

    private final NotifyTargetResolver resolver;
    private final NotifyChannelAdapter adapter;
    private final Sandbox sandbox;

    public NotifyTool(NotifyTargetResolver resolver, NotifyChannelAdapter adapter, Sandbox sandbox) {
        this.resolver = resolver;
        this.adapter = adapter;
        this.sandbox = java.util.Objects.requireNonNull(sandbox, "NotifyTool 必须装配 Sandbox，不允许没有安全边界");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "把一条消息推送到当前 Agent 配置好的通知渠道。"
                + "参数：content（要推送的内容，必填）、channel（可选，渠道类型，缺省使用第一个配置的渠道）。";
    }

    @Override
    public String getInputSchema() {
        return """
                {"type":"object","properties":{"content":{"type":"string","description":"要推送的消息内容"},"channel":{"type":"string","description":"可选的渠道类型，缺省使用第一个配置的渠道"}},"required":["content"]}
                """;
    }

    @Override
    public ToolResult execute(Map<String, Object> input) {
        String content = asString(input == null ? null : input.get(PARAM_CONTENT));
        if (content == null || content.isBlank()) {
            return ToolResult.fail("推送内容不能为空", false);
        }
        String channel = asString(input.get(PARAM_CHANNEL));

        try {
            Profile profile = AgentContext.current();
            NotifyTarget target = resolver.resolve(profile, channel);
            guard(target);
            adapter.send(target, content);
            return ToolResult.ok("已推送");
        } catch (NotifyConfigException e) {
            // 配置问题：重试无用
            return ToolResult.fail(e.getMessage(), false);
        } catch (SandboxViolationException e) {
            // 安全拦截：重试无用
            return ToolResult.fail("通知被安全校验拦截: " + e.getMessage(), false);
        } catch (NotifySendException e) {
            return ToolResult.fail(e.getMessage(), e.isRetryable());
        } catch (RuntimeException e) {
            return ToolResult.fail("通知推送失败: " + e.getMessage(), true);
        }
    }

    /**
     * 发送前的沙箱校验。**必须早于 {@code adapter.send}**。
     *
     * <p>把"推送"表达成一次出站 HTTP 请求：工具不需要知道域名白名单这回事，
     * 换隔离档时这里一行都不用改。</p>
     */
    private void guard(NotifyTarget target) {
        sandbox.enforce(new SandboxAction(ActionType.HTTP_REQUEST, target.configValue("url")));
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
