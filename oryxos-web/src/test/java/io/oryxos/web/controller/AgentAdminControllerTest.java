package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentGenerationException;
import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentNotFoundException;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.profile.SkippedAgent;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 管理端点的 HTTP 表面：状态码、错误体、响应结构。
 *
 * <p>业务正确性（顺序、回滚、幂等）在 core 的测试里断言；这里只关心"前端能不能直接消费"
 * 与"错误码有没有说谎"。</p>
 */
class AgentAdminControllerTest {

    private final AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);
    private final ProfileRegistry registry = mock(ProfileRegistry.class);
    private final SessionManager sessionManager = mock(SessionManager.class);
    private final AgentService agentService = mock(AgentService.class);
    private final MemoryService memoryService = mock(MemoryService.class);

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentAdminController(lifecycle, registry, sessionManager,
                        agentService, memoryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static Profile profile(String name) {
        Profile profile = new Profile();
        profile.setName(name);
        profile.setDescription("演示 Agent");
        Profile.ProviderConfig provider = new Profile.ProviderConfig();
        provider.setName("deepseek");
        provider.setModel("deepseek-flash");
        profile.setProvider(provider);
        return profile;
    }

    @Test
    void 列表返回Agent与被跳过的目录() throws Exception {
        when(registry.getAllProfiles()).thenReturn(Map.of("demo", profile("demo")));
        when(registry.exists("broken")).thenReturn(false);
        when(lifecycle.skippedAgents())
                .thenReturn(List.of(new SkippedAgent("broken", "缺 AGENT.md")));

        mockMvc.perform(get("/api/v1/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agents[0].name").value("demo"))
                .andExpect(jsonPath("$.agents[0].provider").value("deepseek"))
                .andExpect(jsonPath("$.agents[0].fromDirectory").value(false))
                .andExpect(jsonPath("$.skipped[0].dir").value("broken"))
                .andExpect(jsonPath("$.skipped[0].reason").value("缺 AGENT.md"));
    }

    @Test
    void 新建返回201与详情() throws Exception {
        when(lifecycle.create("demo", "演示")).thenReturn(profile("demo"));

        mockMvc.perform(post("/api/v1/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"demo\",\"description\":\"演示\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("demo"))
                .andExpect(jsonPath("$.model").value("deepseek-flash"));

        verify(lifecycle).create("demo", "演示");
    }

    @Test
    void 新建请求体缺失返回400() throws Exception {
        mockMvc.perform(post("/api/v1/agents").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void 更新走PUT并返回新详情() throws Exception {
        when(lifecycle.update(eq("demo"), anyString())).thenReturn(profile("demo"));

        mockMvc.perform(put("/api/v1/agents/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markdown\":\"---\\ndescription: x\\n---\\n正文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("demo"));
    }

    @Test
    void 不存在的Agent返回404() throws Exception {
        when(registry.getProfile("ghost")).thenReturn(null);

        mockMvc.perform(get("/api/v1/agents/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("AGENT_NOT_FOUND"));
    }

    @Test
    void 删除返回归档位置() throws Exception {
        when(lifecycle.delete("demo")).thenReturn(Path.of(".oryxos/archive/demo-20261030-113000"));

        mockMvc.perform(delete("/api/v1/agents/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("demo"))
                .andExpect(jsonPath("$.archivedTo").value("archive/demo-20261030-113000"));
    }

    @Test
    void 生成本不可用时返回503而不是400() throws Exception {
        when(registry.getProfile("demo")).thenReturn(profile("demo"));
        when(lifecycle.generate(eq("demo"), anyString()))
                .thenThrow(AgentGenerationException.unavailable("请配置 oryxos.author.model"));

        mockMvc.perform(post("/api/v1/agents/demo/generate-files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"做一个 PR 摘要\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("GENERATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("oryxos.author.model")));
    }

    @Test
    void 生成结果不可用时返回400() throws Exception {
        when(registry.getProfile("demo")).thenReturn(profile("demo"));
        when(lifecycle.generate(eq("demo"), anyString()))
                .thenThrow(AgentGenerationException.invalid("模型输出缺少 AGENT.md"));

        mockMvc.perform(post("/api/v1/agents/demo/generate-files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"随便\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("GENERATION_FAILED"));
    }

    @Test
    void 生成空描述返回400() throws Exception {
        when(registry.getProfile("demo")).thenReturn(profile("demo"));

        mockMvc.perform(post("/api/v1/agents/demo/generate-files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 保存文件返回写入清单() throws Exception {
        when(registry.getProfile("demo")).thenReturn(profile("demo"));
        when(lifecycle.saveFiles(eq("demo"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(List.of("REFERENCE.md", "AGENT.md"));

        mockMvc.perform(post("/api/v1/agents/demo/files")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"files\":{\"AGENT.md\":\"---\\ndescription: x\\n---\\n正文\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("demo"))
                .andExpect(jsonPath("$.written[0]").value("REFERENCE.md"));
    }

    @Test
    void 记忆快照按Agent读取() throws Exception {
        when(registry.getProfile("demo")).thenReturn(profile("demo"));
        when(memoryService.snapshot((java.nio.file.Path) null)).thenReturn("核心记忆");

        mockMvc.perform(get("/api/v1/agents/demo/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("核心记忆"))
                .andExpect(jsonPath("$.chars").value(4));
    }

    @Test
    void 固定会话返回历史消息() throws Exception {
        Session session = new Session("s-1", "demo", "admin", "console");
        session.addMessage(new Session.Message("user", "你好"));
        session.addMessage(new Session.Message("assistant", "在的"));
        when(registry.getProfile("demo")).thenReturn(profile("demo"));
        when(sessionManager.getOrCreate("admin", "console", "demo")).thenReturn(session);

        mockMvc.perform(get("/api/v1/agents/demo/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.agent").value("demo"))
                .andExpect(jsonPath("$.messages[1].content").value("在的"));
    }

    @Test
    void 会话发消息走同一条Agent处理链路() throws Exception {
        Session session = new Session("s-1", "demo", "admin", "console");
        when(registry.getProfile("demo")).thenReturn(profile("demo"));
        when(sessionManager.getOrCreate("admin", "console", "demo")).thenReturn(session);
        when(agentService.process(session, "帮我看看")).thenReturn("好的");

        mockMvc.perform(post("/api/v1/agents/demo/session/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"帮我看看\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.content").value("好的"));
    }

    @Test
    void 会话发空消息返回400() throws Exception {
        when(registry.getProfile("demo")).thenReturn(profile("demo"));

        mockMvc.perform(post("/api/v1/agents/demo/session/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void Agent不存在时删除也返回404() throws Exception {
        when(lifecycle.delete("ghost")).thenThrow(new AgentNotFoundException("ghost"));

        mockMvc.perform(delete("/api/v1/agents/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("AGENT_NOT_FOUND"));
    }
}
