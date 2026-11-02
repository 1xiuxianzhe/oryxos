package io.oryxos.web.controller;

import io.oryxos.core.memory.MemoryService;
import io.oryxos.web.dto.CatalogDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MemoryApiController —— 长期记忆只读快照。
 *
 * <p>读取必须经过 {@link MemoryService} 门面（宪法原则 V）：Web 层不认识 {@code MEMORY.md}
 * 放在哪、长什么样。将来记忆换成向量库，这个端点一行不用改。</p>
 *
 * <p>返回内容与"注入给模型的上下文"同一口径（归档区按 4000 字符截断），
 * 响应里的 {@code note} 会把这个口径写清楚，避免被当成磁盘原文。</p>
 */
@RestController
@RequestMapping("/api/v1/memory")
public class MemoryApiController {

    /** 口径说明：与注入上下文一致，不做"原文 vs 截断"的歧义。 */
    static final String NOTE = "与注入上下文同一口径：核心记忆完整，归档记忆按 4000 字符截断；只读";

    private final MemoryService memoryService;

    public MemoryApiController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public CatalogDtos.MemorySnapshot snapshot() {
        String content = memoryService.snapshot();
        if (content == null) {
            content = "";
        }
        return new CatalogDtos.MemorySnapshot(content, content.length(), NOTE);
    }
}
