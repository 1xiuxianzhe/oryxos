package io.oryxos.web.controller;

import io.oryxos.web.dto.SessionAuditDtos;
import io.oryxos.web.exception.GlobalExceptionHandler;
import io.oryxos.web.exception.SessionNotFoundException;
import io.oryxos.web.service.SessionAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 运行明细端点的验收：结构可被前端直接消费、未知会话走统一 404。
 *
 * <p>standalone MockMvc + 替身 Service：聚合逻辑在 {@code SessionAuditServiceTest} 里验，
 * 这里只验"HTTP 表面"。</p>
 */
class SessionAuditControllerTest {

    private static final String BASE = "/api/v1/sessions";

    private final SessionAuditService service = mock(SessionAuditService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SessionAuditController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static SessionAuditDtos.SessionAudit sample() {
        SessionAuditDtos.LlmCallView llm = new SessionAuditDtos.LlmCallView(
                "l-1", "deepseek", "deepseek-chat", 500, 60, 560,
                1200L, true, null, "2026-10-21T10:00:00Z");
        SessionAuditDtos.ToolCallView tool = new SessionAuditDtos.ToolCallView(
                "t-1", "get_weather", "{\"city\":\"北京\"}", "晴 21℃", false,
                340L, true, null, "2026-10-21T10:00:00Z");
        SessionAuditDtos.RunView run = new SessionAuditDtos.RunView(
                "r-1", "今天北京天气怎么样", "default", "cli", "deepseek", "deepseek-chat",
                "FINISHED", "FINAL_ANSWER", true, 2, 1, 900, 120, 1020, 2000L, null,
                "2026-10-21T10:00:00Z", "2026-10-21T10:00:02Z", List.of(llm), List.of(tool));
        return new SessionAuditDtos.SessionAudit(
                "s-1", new SessionAuditDtos.Totals(1, 1, 1, 900, 120, 1020, 2000L), List.of(run));
    }

    @Test
    void 返回提问_工具调用与token明细() throws Exception {
        when(service.load("s-1")).thenReturn(sample());

        mockMvc.perform(get(BASE + "/s-1/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.totals.runs").value(1))
                .andExpect(jsonPath("$.totals.totalTokens").value(1020))
                .andExpect(jsonPath("$.runs[0].userMessage").value("今天北京天气怎么样"))
                .andExpect(jsonPath("$.runs[0].stopReason").value("FINAL_ANSWER"))
                .andExpect(jsonPath("$.runs[0].toolCalls[0].toolName").value("get_weather"))
                .andExpect(jsonPath("$.runs[0].llmCalls[0].totalTokens").value(560));

        verify(service).load("s-1");
    }

    @Test
    void 未知会话返回404统一错误体() throws Exception {
        when(service.load("missing")).thenThrow(new SessionNotFoundException("missing"));

        mockMvc.perform(get(BASE + "/missing/runs"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
