package io.oryxos.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentConfigurationException;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.profile.ProfileRegistry;
import io.oryxos.core.react.ModelCallException;
import io.oryxos.core.react.ProcessingTimeoutException;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Agent 对话端点验收：参数校验 → 会话解析 → 委托 AgentService → 错误语义。
 *
 * <p>用 standalone MockMvc + 替身依赖，不启动 Spring 上下文、不连数据库、不调模型。</p>
 */
class AgentApiControllerTest {

    private static final String PATH = "/api/v1/agent/chat";

    private static final Session WEB_SESSION =
            new Session("session-1", "default", AgentApiController.CHANNEL, "anonymous");

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

    private String body(Map<String, Object> fields) throws Exception {
        return objectMapper.writeValueAsString(fields);
    }

    private static Map<String, Object> request(String message) {
        Map<String, Object> map = new HashMap<>();
        if (message != null) {
            map.put("message", message);
        }
        return map;
    }

    // ---------- 正常路径 ----------

    @Test
    void 不传sessionId时按用户身份复用会话并返回回复() throws Exception {
        when(sessionManager.getOrCreate(AgentApiController.CHANNEL, "anonymous", "default"))
                .thenReturn(WEB_SESSION);
        when(agentService.process(WEB_SESSION, "你好")).thenReturn("你好，我是 OryxOS");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(request("你好"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.profile").value("default"))
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content").value("你好，我是 OryxOS"))
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void 显式传入的profile与userId会用于解析会话() throws Exception {
        Session session = new Session("session-9", "demo", AgentApiController.CHANNEL, "u-1");
        when(sessionManager.getOrCreate(AgentApiController.CHANNEL, "u-1", "demo")).thenReturn(session);
        when(agentService.process(session, "hi")).thenReturn("ok");

        Map<String, Object> request = request("hi");
        request.put("profile", "demo");
        request.put("userId", "u-1");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-9"))
                .andExpect(jsonPath("$.profile").value("demo"));
    }

    @Test
    void 传入已存在的sessionId时复用该会话() throws Exception {
        Session existing = new Session("session-42", "default", AgentApiController.CHANNEL, "u-2");
        when(sessionManager.get("session-42")).thenReturn(existing);
        when(agentService.process(existing, "继续")).thenReturn("接着上次说");

        Map<String, Object> request = request("继续");
        request.put("sessionId", "session-42");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-42"))
                .andExpect(jsonPath("$.content").value("接着上次说"));

        // 已有会话走 get(sessionId)，不应再按身份 getOrCreate
        verify(sessionManager, never()).getOrCreate(anyString(), anyString(), anyString());
    }

    @Test
    void 委托给AgentService时不自己实现任何Agent逻辑() throws Exception {
        when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(WEB_SESSION);
        when(agentService.process(any(Session.class), anyString())).thenReturn("reply");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(request("再说一次"))))
                .andExpect(status().isOk());

        verify(agentService).process(eq(WEB_SESSION), eq("再说一次"));
    }

    // ---------- 参数校验 ----------

    @Test
    void message缺失或空白返回400() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(request(null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(request("   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        verifyNoInteractions(agentService, sessionManager);
    }

    @Test
    void message超过32KB返回400且不进入Agent循环() throws Exception {
        String huge = "中".repeat(11 * 1024); // 3 字节/字 → 33KB

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(request(huge))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超出")));

        verifyNoInteractions(agentService);
    }

    // ---------- 错误语义 ----------

    @Test
    void 指定不存在的sessionId返回404() throws Exception {
        when(sessionManager.get("missing")).thenReturn(null);

        Map<String, Object> request = request("hi");
        request.put("sessionId", "missing");

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"));

        verifyNoInteractions(agentService);
    }

    @Test
    void Profile不存在返回400() throws Exception {
        when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(WEB_SESSION);
        when(agentService.process(any(Session.class), anyString()))
                .thenThrow(new AgentConfigurationException("nope"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(request("hi"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AGENT_CONFIG_ERROR"));
    }

    @Test
    void 模型调用失败返回503() throws Exception {
        when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(WEB_SESSION);
        when(agentService.process(any(Session.class), anyString()))
                .thenThrow(new ModelCallException("模型调用失败", new RuntimeException("401")));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(request("hi"))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("PROVIDER_UNAVAILABLE"));
    }

    @Test
    void Agent超时返回504并带已完成进度() throws Exception {
        when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(WEB_SESSION);
        when(agentService.process(any(Session.class), anyString()))
                .thenThrow(new ProcessingTimeoutException(60, 3, 2, null));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(request("hi"))))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.errorCode").value("AGENT_TIMEOUT"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("3 轮")));
    }

    @Test
    void 未知异常返回500() throws Exception {
        when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(WEB_SESSION);
        when(agentService.process(any(Session.class), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(request("hi"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"));
    }

    @Test
    void 错误响应统一带errorCode与timestamp() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(request("  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void 请求体不是合法JSON时返回400而不是500() throws Exception {
        // Windows 路径里的单个反斜杠：JSON 中 \c 不是合法转义。
        // 这类错误属调用方问题，报成 500 INTERNAL_ERROR 会把人往"服务端崩了"的方向带。
        String malformed = "{\"message\":\"在G:\\code\\ORYXOS下添加 test.txt\"}";

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(malformed))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST_BODY"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("反斜杠")));

        verifyNoInteractions(agentService, sessionManager);
    }

    @Test
    void 转义后的Windows路径能正常解析() throws Exception {
        when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(WEB_SESSION);
        when(agentService.process(any(Session.class), anyString())).thenReturn("已写入");
        // 双反斜杠是合法的 JSON 转义，应当通过解析并到达 Agent
        String escaped = "{\"message\":\"在G:\\\\code\\\\ORYXOS下添加 test.txt\"}";

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(escaped))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("已写入"));

        verify(agentService).process(eq(WEB_SESSION), eq("在G:\\code\\ORYXOS下添加 test.txt"));
    }
}
