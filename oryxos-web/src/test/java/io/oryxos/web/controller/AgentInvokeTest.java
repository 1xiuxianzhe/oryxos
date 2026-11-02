package io.oryxos.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 无状态调用端点：未知 Agent 必须在建会话之前被拦下。
 *
 * <p>顺序反了的话，一个拼错的名字会留下一条永远没人看的空会话——
 * 这种"垃圾数据 + 404"的组合最难排查。</p>
 */
class AgentInvokeTest {

    private static final String BASE = "/api/v1/agents";

    private final AgentService agentService = mock(AgentService.class);
    private final SessionManager sessionManager = mock(SessionManager.class);
    private final ProfileRegistry profileRegistry = mock(ProfileRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AgentApiController(agentService, sessionManager, profileRegistry))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void 未知Agent返回404且不创建会话() throws Exception {
        when(profileRegistry.getProfile("nope")).thenReturn(null);

        mockMvc.perform(post(BASE + "/nope/invoke").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "你好"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PROFILE_NOT_FOUND"));

        verify(sessionManager, never()).create(anyString(), anyString(), anyString());
        verify(agentService, never()).process(any(Session.class), anyString());
    }

    @Test
    void 已知Agent用一次性会话完成调用() throws Exception {
        Profile profile = new Profile();
        profile.setName("default");
        when(profileRegistry.getProfile("default")).thenReturn(profile);

        Session session = new Session("s-invoke", "default", AgentApiController.INVOKE_CHANNEL, "u-1");
        when(sessionManager.create("default", AgentApiController.INVOKE_CHANNEL, "u-1")).thenReturn(session);
        when(agentService.process(session, "你好")).thenReturn("pong");

        mockMvc.perform(post(BASE + "/default/invoke").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "你好", "userId", "u-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-invoke"))
                .andExpect(jsonPath("$.agent").value("default"))
                .andExpect(jsonPath("$.content").value("pong"));

        verify(agentService).process(session, "你好");
    }

    @Test
    void 无状态调用的渠道是invoke() throws Exception {
        Profile profile = new Profile();
        profile.setName("default");
        when(profileRegistry.getProfile("default")).thenReturn(profile);
        Session session = new Session("s-invoke", "default", AgentApiController.INVOKE_CHANNEL, "anonymous");
        when(sessionManager.create("default", AgentApiController.INVOKE_CHANNEL, "anonymous")).thenReturn(session);
        when(agentService.process(session, "hi")).thenReturn("ok");

        mockMvc.perform(post(BASE + "/default/invoke").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", "hi"))))
                .andExpect(status().isOk());

        // 渠道区分开，审计里才能一眼看出"这是一次性调用"而不是一段对话
        verify(sessionManager).create("default", "invoke", "anonymous");
    }

    @Test
    void 空白消息返回400且不建会话() throws Exception {
        Profile profile = new Profile();
        profile.setName("default");
        when(profileRegistry.getProfile("default")).thenReturn(profile);

        mockMvc.perform(post(BASE + "/default/invoke").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("message", " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        verify(sessionManager, never()).create(anyString(), anyString(), anyString());
    }
}
