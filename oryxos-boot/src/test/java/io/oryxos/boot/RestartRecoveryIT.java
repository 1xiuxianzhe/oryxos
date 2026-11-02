package io.oryxos.boot;

import io.oryxos.OryxosApplication;
import io.oryxos.boot.support.StubOpenAiServer;
import io.oryxos.boot.support.StubOpenAiServer.ToolCall;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.schedule.AgentScheduler;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.storage.repository.AgentRunRepository;
import io.oryxos.storage.repository.LlmCallRepository;
import io.oryxos.storage.repository.ToolInvocationRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨重启恢复（28 节 §三）。
 *
 * <p>重启是"底座到底有没有状态"的唯一检验方式：内存清空之后，会话、记忆、定时任务、
 * 审计四样东西如果还能各自就位，说明它们真的落在了磁盘上，而不是伪装成持久化的缓存。</p>
 *
 * <p>做法是真的起两次 Spring 上下文（同一个 workspace、同一个 SQLite 文件），
 * 中间关掉第一个——不是 mock 一个"重启"。第一次启动跑一轮带工具的对话，第二次启动
 * 只从磁盘恢复，然后逐项断言。</p>
 */
@Tag("integration")
class RestartRecoveryIT {

    private static final Path WORKSPACE = prepareWorkspace();

    private static final StubOpenAiServer STUB = StubOpenAiServer.start(
            List.of(new ToolCall("read_file", "{\"path\":\"" + helloPath() + "\"}")),
            "HELLO.txt 里是 hello oryxos。");

    @Test
    void 重启后会话_记忆_定时任务与审计都还在() throws IOException {
        String sessionId;

        // ---------- 第一次运行：产生状态 ----------
        try (ConfigurableApplicationContext first = start()) {
            SessionManager sessionManager = first.getBean(SessionManager.class);
            AgentService agentService = first.getBean(AgentService.class);

            Session session = sessionManager.getOrCreate("it", "restart", "smoke");
            sessionId = session.getSessionId();
            agentService.process(session, "HELLO.txt 里写了什么？");

            assertThat(first.getBean(AgentScheduler.class).registeredTasks())
                    .contains("smoke#daily-brief");

            // 记忆：走真实写入路径（核心区 + 归档区各一条），落盘为 memory/MEMORY.md
            MemoryService memory = first.getBean(MemoryService.class);
            memory.remember("用户偏好：中文回答", MemoryScope.CORE);
            memory.remember("上周讨论过部署方案", MemoryScope.ARCHIVAL);
        }

        STUB.reset();

        // ---------- 第二次运行：只从磁盘恢复 ----------
        try (ConfigurableApplicationContext second = start()) {
            // ① 会话：新进程内存里没有它，必须从 SQLite 读回来
            Session recovered = second.getBean(SessionManager.class).get(sessionId);
            assertThat(recovered).isNotNull();
            assertThat(recovered.getSessionId()).isEqualTo(sessionId);
            assertThat(recovered.getMessages()).isNotEmpty();

            // ② 审计：三张表接着可见，且数字与第一次运行时一致
            assertThat(second.getBean(AgentRunRepository.class)
                    .findBySessionIdOrderByStartedAtDesc(sessionId)).hasSize(1);
            assertThat(second.getBean(LlmCallRepository.class).findBySessionId(sessionId)).hasSize(2);
            assertThat(second.getBean(ToolInvocationRepository.class)
                    .findBySessionId(sessionId)).hasSize(1);

            // ③ 记忆：每次读文件，不做缓存——重启后照样读得到，且文件确实落在规范位置
            MemoryService memory = second.getBean(MemoryService.class);
            assertThat(memory.snapshot()).contains("中文回答");
            assertThat(memory.recall("部署")).isNotEmpty();
            assertThat(WORKSPACE.resolve("memory").resolve("MEMORY.md")).exists();

            // ④ 定时任务：启动时按 Profile 重新注册，不需要人工恢复
            assertThat(second.getBean(AgentScheduler.class).registeredTasks())
                    .contains("smoke#daily-brief");
        }
    }

    private static ConfigurableApplicationContext start() {
        // 必须走命令行参数：SpringApplicationBuilder.properties() 是 defaultProperties，
        // 优先级低于 application.yaml，会被里面的 spring.datasource.url 覆盖掉
        return new SpringApplicationBuilder(OryxosApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--oryxos.workspace=" + WORKSPACE,
                        "--oryxos.providers[0].name=stub",
                        "--oryxos.providers[0].api-key=stub-key",
                        "--oryxos.providers[0].base-url=" + STUB.baseUrl(),
                        "--oryxos.sandbox.enabled=true",
                        "--oryxos.sandbox.allowed-paths[0]=" + WORKSPACE,
                        "--spring.datasource.url=jdbc:sqlite:" + sqlitePath(),
                        "--logging.level.io.oryxos=WARN",
                        "--logging.level.root=WARN");
    }

    private static String sqlitePath() {
        return WORKSPACE.resolve("restart.db").toString().replace('\\', '/');
    }

    private static Path prepareWorkspace() {
        try {
            Path dir = Files.createTempDirectory("oryxos-restart-");
            Files.createDirectories(dir.resolve("profiles"));
            Files.writeString(dir.resolve("profiles/smoke.yaml"), """
                    description: 跨重启恢复验收用 Agent
                    identity: 记得上下文的助手
                    provider:
                      name: stub
                      model: stub-model
                    tools:
                      - name: read_file
                    settings:
                      max_iterations: 5
                      max_history_turns: 10
                    schedules:
                      - id: daily-brief
                        cron: "0 0 3 * * *"
                        message: "读 HELLO.txt 并给出摘要"
                    """, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("HELLO.txt"), "hello oryxos", StandardCharsets.UTF_8);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("准备工作区失败", e);
        }
    }

    private static String helloPath() {
        return WORKSPACE.resolve("HELLO.txt").toString().replace('\\', '/');
    }
}
