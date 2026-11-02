package io.oryxos.web.controller;

import io.oryxos.web.dto.SessionAuditDtos;
import io.oryxos.web.service.SessionAuditService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SessionAuditController —— 会话的"账本"视图（只读）。
 *
 * <p>与管理平台会话详情配合使用：{@code GET /sessions/{id}} 回答"聊了什么"，
 * 本端点回答"问了哪些问题、调了哪些工具、花了多少 token"。两者分开是刻意的——
 * 消息读内存会话，明细读 SQLite 审计表，聚合代价不同，不该捆在一个响应里。</p>
 *
 * <p>只读语义与其余查询端点一致：核心阶段不做审计查询的写操作，也不做报表导出。</p>
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionAuditController {

    private final SessionAuditService sessionAuditService;

    public SessionAuditController(SessionAuditService sessionAuditService) {
        this.sessionAuditService = sessionAuditService;
    }

    /** 会话运行明细：每次提问 → 模型调用（token）→ 工具调用。不存在 → 404。 */
    @GetMapping("/{id}/runs")
    public SessionAuditDtos.SessionAudit runs(@PathVariable String id) {
        return sessionAuditService.load(id);
    }
}
