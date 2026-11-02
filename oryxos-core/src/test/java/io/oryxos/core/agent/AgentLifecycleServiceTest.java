package io.oryxos.core.agent;

import io.oryxos.core.llm.LlmClient;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.profile.AgentLoader;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.profile.SkippedAgent;
import io.oryxos.core.schedule.AgentScheduler;
import io.oryxos.core.session.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生命周期编排：注册顺序、失败回滚、幂等重注册、生成与保存。
 *
 * <p>重点不在"happy path 能跑通"，而在三条容易被写错的顺序：注册前先注销旧定时、
 * 更新前先校验再写盘、删除时先停定时再归档。</p>
 */
class AgentLifecycleServiceTest {

    @TempDir
    Path workspace;

    private final ProfileRegistry registry = new ProfileRegistry();
    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final AgentService agentService = mock(AgentService.class);
    private final SessionManager sessionManager = new SessionManager();

    private AgentStore store;
    private AgentLoader loader;
    private AgentScheduler scheduler;
    private AgentLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        doReturn(mock(ScheduledFuture.class))
                .when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
        store = new AgentStore(workspace, "deepseek", "deepseek-flash");
        loader = new AgentLoader(store.agentsDir(), Set.of("deepseek"), registry);
        scheduler = new AgentScheduler(taskScheduler, registry, sessionManager, agentService);
        lifecycle = new AgentLifecycleService(store, loader, registry, scheduler,
                null, "deepseek", "deepseek-flash", List.of("read_file"));
    }

    private static String markdown(String description, String cron) {
        return """
                ---
                description: %s
                provider:
                  name: deepseek
                  model: deepseek-flash
                schedules:
                  - id: daily
                    cron: "%s"
                    timezone: Asia/Shanghai
                    message: 干活
                ---

                你是测试 Agent。
                """.formatted(description, cron);
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    @Test
    void 注册顺序为_注销旧定时_写入索引_重新登记() throws Exception {
        ProfileRegistry registryMock = mock(ProfileRegistry.class);
        AgentScheduler schedulerMock = mock(AgentScheduler.class);
        AgentLifecycleService service = new AgentLifecycleService(
                store, loader, registryMock, schedulerMock, null, null, null, List.of());
        store.createScaffold("daily-pr", "摘要");

        service.register("daily-pr");

        InOrder order = inOrder(schedulerMock, registryMock);
        order.verify(schedulerMock).unregisterProfile("daily-pr");
        order.verify(registryMock).register(any(Profile.class));
        order.verify(schedulerMock).registerProfile(any(Profile.class));
    }

    @Test
    void 注册同一目录两次只保留一份定时() throws Exception {
        ScheduledFuture<?> first = mock(ScheduledFuture.class);
        doReturn(first, mock(ScheduledFuture.class))
                .when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
        lifecycle.create("daily-pr", "摘要");
        lifecycle.update("daily-pr", markdown("摘要", "0 0 9 * * *"));

        lifecycle.register("daily-pr");

        // 旧句柄必须被取消：否则改一次 schedules 就有两条 cron 一起跑
        verify(first).cancel(false);
        assertThat(scheduler.registeredTasksOf("daily-pr")).containsExactly("daily-pr#daily");
    }

    @Test
    void 目录里没有AGENT主文件时注册失败且不影响已有注册() {
        assertThatThrownBy(() -> lifecycle.register("ghost"))
                .isInstanceOf(AgentDefinitionException.class)
                .hasMessageContaining("AGENT.md");
        assertThat(registry.exists("ghost")).isFalse();
    }

    // ------------------------------------------------------------------
    // 创建
    // ------------------------------------------------------------------

    @Test
    void 创建后目录与注册表同时存在() {
        Profile profile = lifecycle.create("daily-pr", "每天汇总 PR");

        assertThat(store.exists("daily-pr")).isTrue();
        assertThat(registry.getProfile("daily-pr")).isSameAs(profile);
        assertThat(profile.getAgentDir()).isEqualTo(store.agentDir("daily-pr"));
        assertThat(profile.getDescription()).isEqualTo("每天汇总 PR");
    }

    @Test
    void 名字非法或重名时一个目录都不写() {
        lifecycle.create("daily-pr", "第一次");

        assertThatThrownBy(() -> lifecycle.create("daily-pr", "第二次"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
        assertThatThrownBy(() -> lifecycle.create("Bad_Name", "非法"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不合法");

        try (var stream = Files.list(store.agentsDir())) {
            assertThat(stream.filter(Files::isDirectory)).hasSize(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        // 重名没有覆盖原内容
        assertThat(registry.getProfile("daily-pr").getDescription()).isEqualTo("第一次");
    }

    @Test
    void 注册失败时已写下的目录会被回滚删除() {
        AgentStore undeclared = new AgentStore(workspace, "ghost", null);
        AgentLifecycleService service = new AgentLifecycleService(undeclared,
                new AgentLoader(undeclared.agentsDir(), Set.of("deepseek"), registry),
                registry, scheduler, null, null, null, List.of());

        assertThatThrownBy(() -> service.create("bad-provider", "引用了未声明的 provider"))
                .isInstanceOf(AgentDefinitionException.class)
                .hasMessageContaining("ghost");

        assertThat(undeclared.exists("bad-provider")).isFalse();
        assertThat(registry.exists("bad-provider")).isFalse();
    }

    // ------------------------------------------------------------------
    // 更新
    // ------------------------------------------------------------------

    @Test
    void 更新非法内容时磁盘逐字不变且注册表仍是旧版本() throws Exception {
        lifecycle.create("daily-pr", "初始描述");
        Path mainFile = store.agentDir("daily-pr").resolve("AGENT.md");
        String before = Files.readString(mainFile, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> lifecycle.update("daily-pr", "只有一句话，没有 frontmatter"))
                .isInstanceOf(AgentDefinitionException.class)
                .hasMessageContaining("provider");

        assertThat(Files.readString(mainFile, StandardCharsets.UTF_8)).isEqualTo(before);
        assertThat(registry.getProfile("daily-pr").getDescription()).isEqualTo("初始描述");
    }

    @Test
    void 更新成功后注册表与磁盘都是新内容() throws Exception {
        lifecycle.create("daily-pr", "初始描述");

        lifecycle.update("daily-pr", markdown("新描述", "0 0 9 * * *"));

        assertThat(registry.getProfile("daily-pr").getDescription()).isEqualTo("新描述");
        assertThat(Files.readString(store.agentDir("daily-pr").resolve("AGENT.md"), StandardCharsets.UTF_8))
                .contains("新描述");
        assertThat(scheduler.registeredTasksOf("daily-pr")).containsExactly("daily-pr#daily");
    }

    @Test
    void 更新不存在的Agent报404语义() {
        assertThatThrownBy(() -> lifecycle.update("ghost", markdown("x", "0 0 9 * * *")))
                .isInstanceOf(AgentNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // 删除
    // ------------------------------------------------------------------

    @Test
    void 删除顺序为_注销定时_移出索引_归档() throws Exception {
        ProfileRegistry registryMock = mock(ProfileRegistry.class);
        AgentScheduler schedulerMock = mock(AgentScheduler.class);
        AgentStore storeMock = mock(AgentStore.class);
        AgentLifecycleService service = new AgentLifecycleService(
                storeMock, loader, registryMock, schedulerMock, null, null, null, List.of());
        when(storeMock.exists("old-agent")).thenReturn(true);
        when(storeMock.agentDir("old-agent")).thenReturn(workspace.resolve("agents/old-agent"));
        when(storeMock.archive("old-agent")).thenReturn(workspace.resolve("archive/old-agent-1"));

        service.delete("old-agent");

        InOrder order = inOrder(schedulerMock, registryMock, storeMock);
        order.verify(schedulerMock).unregisterProfile("old-agent");
        order.verify(registryMock).remove("old-agent");
        order.verify(storeMock).archive("old-agent");
    }

    @Test
    void 删除后目录进归档而不是消失() {
        lifecycle.create("daily-pr", "摘要");
        Path original = store.agentDir("daily-pr");

        Path archived = lifecycle.delete("daily-pr");

        assertThat(original).doesNotExist();
        assertThat(archived).isNotNull();
        assertThat(archived.resolve("AGENT.md")).isRegularFile();
        assertThat(registry.exists("daily-pr")).isFalse();
        assertThat(scheduler.registeredTasksOf("daily-pr")).isEmpty();
    }

    @Test
    void 删除不存在的Agent报404语义() {
        assertThatThrownBy(() -> lifecycle.delete("ghost"))
                .isInstanceOf(AgentNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // 生成
    // ------------------------------------------------------------------

    @Test
    void 未配置生成模型时返回不可用并点名配置键() {
        assertThatThrownBy(() -> lifecycle.generate("draft", "帮我做一个 PR 摘要 Agent"))
                .isInstanceOfSatisfying(AgentGenerationException.class, e -> {
                    assertThat(e.unavailable()).isTrue();
                    assertThat(e.getMessage()).contains("LlmClient");
                });

        AgentLifecycleService noModel = new AgentLifecycleService(store, loader, registry, scheduler,
                mock(LlmClient.class), "deepseek", null, List.of());
        assertThatThrownBy(() -> noModel.generate("draft", "帮我做一个 PR 摘要 Agent"))
                .isInstanceOfSatisfying(AgentGenerationException.class, e -> {
                    assertThat(e.unavailable()).isTrue();
                    assertThat(e.getMessage()).contains("oryxos.author.model");
                });
    }

    @Test
    void 生成结果解析为文件且不落盘() {
        LlmClient client = mock(LlmClient.class);
        when(client.call(any(), any(), any())).thenReturn(new LlmClient.LlmResponse("""
                === FILE: AGENT.md ===
                ```
                ---
                description: 生成出来的
                provider:
                  name: deepseek
                  model: deepseek-flash
                ---

                你是生成出来的 Agent。
                ```
                === FILE: REFERENCE.md ===
                # 资料

                写在这里。
                """, List.of(), null));
        AgentLifecycleService service = new AgentLifecycleService(store, loader, registry, scheduler,
                client, "deepseek", "deepseek-flash", List.of("read_file"));

        Map<String, String> files = service.generate("generated", "做一个会总结的 Agent");

        assertThat(files).containsOnlyKeys("AGENT.md", "REFERENCE.md");
        assertThat(files.get("AGENT.md")).startsWith("---").doesNotContain("```");
        assertThat(store.exists("generated")).isFalse();
        assertThat(registry.exists("generated")).isFalse();
    }

    @Test
    void 生成内容无法解析时按调用方错误处理() {
        LlmClient client = mock(LlmClient.class);
        when(client.call(any(), any(), any()))
                .thenReturn(new LlmClient.LlmResponse("我觉得这个 Agent 应该很聪明。", List.of(), null));
        AgentLifecycleService service = new AgentLifecycleService(store, loader, registry, scheduler,
                client, "deepseek", "deepseek-flash", List.of("read_file"));

        assertThatThrownBy(() -> service.generate("generated", "随便"))
                .isInstanceOfSatisfying(AgentGenerationException.class, e -> {
                    assertThat(e.unavailable()).isFalse();
                    assertThat(e.getMessage()).contains("provider");
                });
    }

    @Test
    void 生成调用使用固定的作者会话() {
        LlmClient client = mock(LlmClient.class);
        when(client.call(any(), any(), any())).thenReturn(new LlmClient.LlmResponse("""
                ---
                description: 生成
                provider:
                  name: deepseek
                  model: deepseek-flash
                ---

                正文
                """, List.of(), null));
        AgentLifecycleService service = new AgentLifecycleService(store, loader, registry, scheduler,
                client, "deepseek", "deepseek-flash", List.of("read_file"));

        service.generate("generated", "描述");

        verify(client).call(org.mockito.ArgumentMatchers.eq("author"), any(), any());
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    @Test
    void 保存时AGENT主文件非法则一个字节都不写() throws Exception {
        lifecycle.create("daily-pr", "初始描述");
        Path dir = store.agentDir("daily-pr");
        String referenceBefore = Files.readString(dir.resolve("REFERENCE.md"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> lifecycle.saveFiles("daily-pr", Map.of(
                "AGENT.md", "没有 frontmatter",
                "REFERENCE.md", "这段不该被写进去")))
                .isInstanceOf(AgentDefinitionException.class);

        assertThat(Files.readString(dir.resolve("REFERENCE.md"), StandardCharsets.UTF_8))
                .isEqualTo(referenceBefore);
    }

    @Test
    void 保存合法文件后重注册并落盘() throws Exception {
        lifecycle.create("daily-pr", "初始描述");

        List<String> written = lifecycle.saveFiles("daily-pr", Map.of(
                "AGENT.md", markdown("保存后的描述", "0 0 9 * * *"),
                "REFERENCE.md", "# 新资料"));

        assertThat(written).contains("AGENT.md", "REFERENCE.md");
        assertThat(registry.getProfile("daily-pr").getDescription()).isEqualTo("保存后的描述");
        assertThat(Files.readString(store.agentDir("daily-pr").resolve("REFERENCE.md"), StandardCharsets.UTF_8))
                .isEqualTo("# 新资料");
    }

    @Test
    void 保存时拒绝越界路径() {
        lifecycle.create("daily-pr", "初始描述");

        assertThatThrownBy(() -> lifecycle.saveFiles("daily-pr", Map.of("../escape.md", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
        assertThatThrownBy(() -> lifecycle.saveFiles("daily-pr", Map.of("/etc/passwd", "x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相对路径");
    }

    // ------------------------------------------------------------------
    // 监听回调
    // ------------------------------------------------------------------

    @Test
    void 监听回调遇到非法内容时保留旧注册并记录原因() throws Exception {
        lifecycle.create("daily-pr", "初始描述");
        Path dir = store.agentDir("daily-pr");
        store.writeAtomically(dir.resolve("AGENT.md"), "半写的非法内容");

        lifecycle.onChanged(dir);

        assertThat(registry.getProfile("daily-pr")).isNotNull();
        assertThat(lifecycle.skippedAgents()).extracting(SkippedAgent::dirName).contains("daily-pr");
    }

    @Test
    void 监听回调发现目录消失时注销且不误伤legacy同名() {
        lifecycle.create("daily-pr", "初始描述");
        lifecycle.onRemoved("daily-pr");
        assertThat(registry.exists("daily-pr")).isFalse();

        Profile legacy = new Profile();
        legacy.setName("legacy-only");
        registry.register(legacy);
        lifecycle.onRemoved("legacy-only");
        assertThat(registry.exists("legacy-only")).isTrue();
    }
}
