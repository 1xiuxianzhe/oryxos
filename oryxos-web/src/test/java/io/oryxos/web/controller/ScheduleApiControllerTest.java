package io.oryxos.web.controller;

import io.oryxos.web.dto.ScheduleDtos;
import io.oryxos.web.exception.GlobalExceptionHandler;
import io.oryxos.web.service.ScheduleQueryService;
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

/** 定时任务端点的 HTTP 表面：结构可被前端直接消费。 */
class ScheduleApiControllerTest {

    private final ScheduleQueryService service = mock(ScheduleQueryService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ScheduleApiController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static ScheduleDtos.ScheduleView sample() {
        ScheduleDtos.RunBrief last = new ScheduleDtos.RunBrief(
                "r-1", "FINISHED", "FINAL_ANSWER", true, 2, 1, 250, 50, 300,
                1200L, null, "2026-10-22T19:00:00Z", "2026-10-22T19:00:01.2Z");
        ScheduleDtos.ScheduleTask task = new ScheduleDtos.ScheduleTask(
                "smoke#daily-brief", "smoke", "daily-brief", "0 0 3 * * *", "Asia/Shanghai",
                "读 HELLO.txt 并给出摘要", false, null, "2026-10-23T03:00:00+08:00",
                "sess-1", 5L, last, List.of(last));
        return new ScheduleDtos.ScheduleView(
                new ScheduleDtos.Overview(2, 1, 0, 1),
                List.of(task),
                List.of(new ScheduleDtos.SkippedTask("broken", "bad", "nope", "cron 非法")));
    }

    @Test
    void 返回任务列表_运行史与跳过项() throws Exception {
        when(service.snapshot()).thenReturn(sample());

        mockMvc.perform(get("/api/v1/schedules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduler.poolSize").value(2))
                .andExpect(jsonPath("$.scheduler.registered").value(1))
                .andExpect(jsonPath("$.tasks[0].key").value("smoke#daily-brief"))
                .andExpect(jsonPath("$.tasks[0].cron").value("0 0 3 * * *"))
                .andExpect(jsonPath("$.tasks[0].nextRunAt").value("2026-10-23T03:00:00+08:00"))
                .andExpect(jsonPath("$.tasks[0].sessionId").value("sess-1"))
                .andExpect(jsonPath("$.tasks[0].lastRun.stopReason").value("FINAL_ANSWER"))
                .andExpect(jsonPath("$.skipped[0].reason").value("cron 非法"));

        verify(service).snapshot();
    }
}
