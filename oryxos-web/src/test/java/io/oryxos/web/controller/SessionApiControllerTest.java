package io.oryxos.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.oryxos.core.agent.AgentService;
import io.oryxos.core.session.Session;
import io.oryxos.core.session.SessionManager;
import io.oryxos.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 会话端点验收：参数校验、委托次数、错误语义、截断口径。
 *
 * <p>全部用 standalone MockMvc + 替身依赖：不起 Spring 上下文、不连库、不调模型。</p>
 *
 * <p>最关键的一条是"拒绝必须发生在引擎之前"——超限或空白消息若先进入 {@code AgentService}，
 * 就等于白烧一次模型调用，还会在审计里留下一条奇怪的记录。</p>
 */
class SessionApiControllerTest {

    private static final String BASE = "/api/v1/sessions";

    private final AgentService agentService = mock(AgentService.class);
    private final SessionManager sessionManager = mock(SessionManager.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SessionApiController(agentService, sessionManager))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static Session session(String id, String profile, String user) {
        return new Session(id, profile, SessionApiController.CHANNEL, user);
    }

    // ---------- 创建 ----------

    @Test
    void 创建会话固定web渠道并回填默认值() throws Exception {
        Session created = session("s-1", "default", "anonymous");
        when(sessionManager.getOrCreate(SessionApiController.CHANNEL, "anonymous", "default"))
                .thenReturn(created);

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.channel").value("web"))
                .andExpect(jsonPath("$.profileName").value("default"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.messageCount").value(0));

        // 创建即落存储：否则重启后管理平台看不到这个会话
        verify(sessionManager).save(created);
    }

    @Test
    void 创建会话使用请求里的profile与user() throws Exception {
        Session created = session("s-2", "weather", "u-1");
        when(sessionManager.getOrCreate(SessionApiController.CHANNEL, "u-1", "weather"))
                .thenReturn(created);

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("profile", "weather", "userId", "u-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileName").value("weather"))
                .andExpect(jsonPath("$.userId").value("u-1"));
    }

    @Test
    void 重复创建返回已有会话而不是新建() throws Exception {
        // 幂等的判据：走 getOrCreate、不调 create——硬插会撞 (channel,user_id,profile_name) 唯一约束
        Session existing = session("s-existing", "default", "u-1");
        when(sessionManager.getOrCreate(SessionApiController.CHANNEL, "u-1", "default"))
                .thenReturn(existing);

        mockMvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("userId", "u-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-existing"));

        verify(sessionManager, never()).create(anyString(), anyString(), anyString());
        verify(sessionManager).save(existing);
    }

    // ---------- 列表 ----------

    @Test
    void 列表按最近活跃倒序且只给计数() throws Exception {
        Session older = session("s-old", "default", "u-1");
        older.restore(List.of(new Session.Message("user", "旧消息")), "active",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"), null);
        Session newer = session("s-new", "default", "u-2");
        newer.restore(List.of(), "active",
                Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-02-02T00:00:00Z"), null);
        when(sessionManager.list()).thenReturn(List.of(older, newer));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sessionId").value("s-new"))
                .andExpect(jsonPath("$[1].sessionId").value("s-old"))
                .andExpect(jsonPath("$[1].messageCount").value(1))
                .andExpect(jsonPath("$[1].messages").doesNotExist());
    }

    // ---------- 详情 ----------

    @Test
    void 详情最多返回最近一百条并声明上限() throws Exception {
        Session session = session("s-1", "default", "u-1");
        for (int i = 0; i < 150; i++) {
            session.addMessage(new Session.Message(i % 2 == 0 ? "user" : "assistant", "m-" + i));
        }
        when(sessionManager.get("s-1")).thenReturn(session);

        mockMvc.perform(get(BASE + "/s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageLimit").value(100))
                .andExpect(jsonPath("$.messages.length()").value(100))
                .andExpect(jsonPath("$.messages[0].content").value("m-50"))
                .andExpect(jsonPath("$.messages[99].content").value("m-149"))
                .andExpect(jsonPath("$.session.messageCount").value(150));
    }

    @Test
    void 详情会话不存在返回404() throws Exception {
        when(sessionManager.get("nope")).thenReturn(null);

        mockMvc.perform(get(BASE + "/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ---------- 发消息 ----------

    @Test
    void 正常发消息委托引擎一次并返回assistant() throws Exception {
        Session session = session("s-1", "default", "u-1");
        when(sessionManager.get("s-1")).thenReturn(session);
        when(agentService.process(session, "你好")).thenReturn("你好，我是 OryxOS");

        mockMvc.perform(post(BASE + "/s-1/messages").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "你好"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content").value("你好，我是 OryxOS"))
                .andExpect(jsonPath("$.status").value("ok"));

        verify(agentService).process(session, "你好");
    }

    @Test
    void 空白消息被拒绝且不进入引擎() throws Exception {
        mockMvc.perform(post(BASE + "/s-1/messages").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));

        verify(agentService, never()).process(any(Session.class), anyString());
    }

    @Test
    void 超32KB按UTF8字节拒绝且不进入引擎() throws Exception {
        String huge = "a".repeat(40_000);

        mockMvc.perform(post(BASE + "/s-1/messages").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", huge))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("超出单条上限")));

        verify(agentService, never()).process(any(Session.class), anyString());
    }

    @Test
    void 会话不存在返回404且不进入引擎() throws Exception {
        when(sessionManager.get("nope")).thenReturn(null);

        mockMvc.perform(post(BASE + "/nope/messages").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "hi"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"));

        verify(agentService, never()).process(any(Session.class), anyString());
    }

    @Test
    void 已归档会话不能继续发消息() throws Exception {
        Session archived = session("s-arch", "default", "u-1");
        archived.archive();
        when(sessionManager.get("s-arch")).thenReturn(archived);

        mockMvc.perform(post(BASE + "/s-arch/messages").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "hi"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SESSION_ARCHIVED"));

        verify(agentService, never()).process(any(Session.class), anyString());
    }

    @Test
    void 请求体不是合法JSON时返回400而不是500() throws Exception {
        mockMvc.perform(post(BASE + "/s-1/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"G:\\code\\ORYXOS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST_BODY"));
    }

    @Test
    void 内部异常细节绝不能出现在500响应里() throws Exception {
        Session session = session("s-1", "default", "u-1");
        when(sessionManager.get("s-1")).thenReturn(session);
        when(agentService.process(any(Session.class), anyString()))
                .thenThrow(new IllegalStateException("jdbc:sqlite:/data/oryxos.db connect failed"));

        mockMvc.perform(post(BASE + "/s-1/messages").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "hi"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE))
                // 连接串这类内幕一个字都不许漏
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("jdbc:sqlite"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("IllegalStateException"))));
    }

    // ---------- 归档 ----------

    @Test
    void 归档会话标记状态而不删数据() throws Exception {
        when(sessionManager.get("s-1")).thenReturn(session("s-1", "default", "u-1"));

        mockMvc.perform(delete(BASE + "/s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.status").value("archived"));

        verify(sessionManager).archive(eq("s-1"));
    }

    @Test
    void 归档不存在的会话返回404() throws Exception {
        when(sessionManager.get("nope")).thenReturn(null);

        mockMvc.perform(delete(BASE + "/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"));

        verify(sessionManager, never()).archive(anyString());
    }

    @Test
    void 摘要时间字段是ISO字符串() throws Exception {
        Session session = session("s-1", "default", "u-1");
        when(sessionManager.get("s-1")).thenReturn(session);

        mockMvc.perform(get(BASE + "/s-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.createdAt").value(org.hamcrest.Matchers.containsString("T")));
    }
}
