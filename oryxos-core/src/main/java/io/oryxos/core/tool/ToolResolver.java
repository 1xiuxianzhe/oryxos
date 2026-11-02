package io.oryxos.core.tool;

import io.oryxos.core.profile.Profile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * ToolResolver —— 按当前 Agent 的授权声明解析出本轮可用的工具。
 *
 * <p>这是核心阶段唯一的工具治理手段：让"运维助手只给读文件和执行命令、
 * 客服助手只给某个业务接口"变成可配置的一件事。</p>
 *
 * <p><b>语义</b>（见 {@code specs/005-tool-system/contracts/tool-authorization.md}）：</p>
 * <ul>
 *   <li>未声明或声明为空 → <b>不限制</b>，返回注册表全部。刻意与现行为兼容：
 *       默认模板就是空清单，若把空当成"无工具"，升级后默认 Agent 会立刻失去全部能力。</li>
 *   <li>声明了 N 个名字 → 返回<b>恰好</b>这 N 个（以注册表中实际存在的为准）。</li>
 *   <li>声明了不存在的名字 → 输出可诊断告警，<b>不</b>静默忽略、<b>不</b>回退成全量。</li>
 * </ul>
 *
 * <p><b>粒度</b>：按 Agent 各自计算，不缓存跨 Agent 复用。若把结果缓存在全局变量里，
 * 第二个 Agent 会拿到第一个的工具集——这类缺陷在单 Agent 场景下测不出来。</p>
 *
 * <p>过滤<b>不区分来源</b>：外部工具服务提供的工具与内置工具在注册表中同形态，
 * 因此同样受授权声明约束。</p>
 */
public class ToolResolver {

    private static final Logger logger = LoggerFactory.getLogger(ToolResolver.class);

    private final ToolRegistry toolRegistry;

    public ToolResolver(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 解析该 Agent 本轮可用的工具。
     *
     * @param profile 当前 Agent 配置；为 {@code null} 时退化为不限制
     * @return 可交给 Prompt 组装与模型调用的工具列表
     */
    public List<OryxTool> resolve(Profile profile) {
        List<OryxTool> all = toolRegistry.listAll();
        List<String> declared = declaredNames(profile);
        if (declared.isEmpty()) {
            return all;
        }

        List<String> missing = declared.stream()
                .filter(name -> name != null && !name.isBlank() && !toolRegistry.contains(name))
                .toList();
        if (!missing.isEmpty()) {
            logger.warn("[Tool] Agent [{}] 声明了不存在的工具 {}，已从可用集合中排除；当前注册表: {}",
                    profile == null ? "<无上下文>" : profile.getName(),
                    missing, all.stream().map(OryxTool::getName).toList());
        }

        return declared.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .map(toolRegistry::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 读取 Agent 配置中声明的工具名；未声明或为空返回空列表（表示不限制）。 */
    private static List<String> declaredNames(Profile profile) {
        if (profile == null || profile.getTools() == null) {
            return List.of();
        }
        return profile.getTools().stream()
                .map(Profile.ToolConfig::getName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }
}
