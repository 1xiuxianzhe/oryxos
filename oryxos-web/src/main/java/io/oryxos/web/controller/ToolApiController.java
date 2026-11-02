package io.oryxos.web.controller;

import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ToolApiController —— 工具清单端点。
 *
 * <p>内容直接来自注册表，是"当前到底有哪些工具"的唯一真相来源；
 * <b>不</b>使用硬编码清单，也<b>不</b>用目录列举替代——两者都会与实际状态不符，
 * 在排查"Agent 为什么不调某个工具"时直接误导方向。</p>
 *
 * <p>注册表为空时返回空数组（不是报错、也不是一份非空的假清单），由调用方自行判断。</p>
 */
@RestController
@RequestMapping("/api/v1/tools")
public class ToolApiController {

    private final ToolRegistry toolRegistry;

    public ToolApiController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public List<Map<String, Object>> listTools() {
        return toolRegistry.listAll().stream()
                .sorted(Comparator.comparing(OryxTool::getName))
                .map(tool -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", tool.getName());
                    item.put("description", tool.getDescription());
                    return item;
                })
                .toList();
    }
}
