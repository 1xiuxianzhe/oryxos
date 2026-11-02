package io.oryxos.web.controller;

import io.oryxos.web.dto.ScheduleDtos;
import io.oryxos.web.service.ScheduleQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ScheduleApiController —— 定时任务的只读视图。
 *
 * <p>回答三个问题：配了哪些任务（cron / 时区 / 消息）、下次什么时候跑、跑到什么结果。
 * 不提供手动触发与修改：核心阶段"改配置要重启"是既定行为。</p>
 */
@RestController
@RequestMapping("/api/v1/schedules")
public class ScheduleApiController {

    private final ScheduleQueryService scheduleQueryService;

    public ScheduleApiController(ScheduleQueryService scheduleQueryService) {
        this.scheduleQueryService = scheduleQueryService;
    }

    /** 定时任务全景：注册态 + 运行史 + 被跳过的配置。 */
    @GetMapping
    public ScheduleDtos.ScheduleView list() {
        return scheduleQueryService.snapshot();
    }
}
