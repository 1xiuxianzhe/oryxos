package io.oryxos.core.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ToolRegistry —— 统一管理所有 Tool。
 *
 * <p>内置 Tool、Plugin Tool、外部工具服务的 Tool 全部包装成 {@link OryxTool} 注册进来，
 * 使用方（含 ReAct 循环）不感知工具来源。</p>
 *
 * <p>注册表是"当前到底有哪些工具"的<b>唯一真相来源</b>：工具清单的命令行入口与 HTTP 入口、
 * 以及 Agent 组装可用工具，都以它为准，不得各自维护第二份清单或缓存副本。</p>
 */
public class ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, OryxTool> tools = new ConcurrentHashMap<>();

    /**
     * 注册一个工具。
     *
     * <p><b>重名拒绝</b>：名字已被占用时拒绝本次注册并告警，保留先注册者。
     * 静默覆盖会让"某个来源把另一个来源顶掉"无声发生，排查时表现为"工具明明注册了却调不到"。</p>
     *
     * <p>注册顺序为"内置工具 → 外部工具服务"，因此撞名时<b>内置工具胜出</b>——
     * 内置能力是地基，不应被外部依赖顶掉。被拒的一方必须有告警，否则撞名难以发现。</p>
     */
    public void register(OryxTool tool) {
        OryxTool existing = tools.putIfAbsent(tool.getName(), tool);
        if (existing != null) {
            logger.warn("[Tool] 工具名已被占用，拒绝注册: name={}, 现有实现={}, 被拒实现={}",
                    tool.getName(), existing.getClass().getSimpleName(), tool.getClass().getSimpleName());
        }
    }

    public OryxTool get(String name) {
        return tools.get(name);
    }

    /** 该名字是否已登记。工具清单入口与测试用它判断存在性。 */
    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public Map<String, OryxTool> all() {
        return tools;
    }

    public List<OryxTool> listAll() {
        return List.copyOf(tools.values());
    }
}
