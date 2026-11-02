package io.oryxos.web.exception;

import io.oryxos.core.agent.AgentConfigurationException;
import io.oryxos.core.react.ModelCallException;
import io.oryxos.core.react.ProcessingTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误出口契约：状态码映射 + 三字段结构 + 500 不泄漏。
 *
 * <p>直接调处理器而不起 MockMvc：这里验的是"映射表本身"，
 * 走一遍 HTTP 只会让断言变慢、失败信息变模糊。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static void assertBody(ResponseEntity<ErrorBody> response,
                                   HttpStatus expectedStatus,
                                   String expectedCode) {
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        ErrorBody body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.errorCode()).isEqualTo(expectedCode);
        assertThat(body.message()).isNotBlank();
        // 时间戳必须是可解析的 ISO-8601，否则对接方解析会炸
        assertThat(Instant.parse(body.timestamp())).isNotNull();
    }

    @Test
    void 参数错误映射400() {
        assertBody(handler.handleBadRequest(new IllegalArgumentException("content 不能为空")),
                HttpStatus.BAD_REQUEST, "BAD_REQUEST");
    }

    @Test
    void 会话不存在映射404() {
        assertBody(handler.handleSessionNotFound(new SessionNotFoundException("s-1")),
                HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND");
    }

    @Test
    void 会话已归档映射400而不是404() {
        // 资源在、状态不允许写：报 404 会让人去查存储，方向就错了
        assertBody(handler.handleSessionArchived(new SessionArchivedException("s-1")),
                HttpStatus.BAD_REQUEST, "SESSION_ARCHIVED");
    }

    @Test
    void Agent不存在映射404() {
        assertBody(handler.handleProfileNotFound(new ProfileNotFoundException("nope")),
                HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND");
    }

    @Test
    void Profile配置缺陷映射400() {
        assertBody(handler.handleAgentConfig(new AgentConfigurationException("broken")),
                HttpStatus.BAD_REQUEST, "AGENT_CONFIG_ERROR");
    }

    @Test
    void Provider故障映射503() {
        assertBody(handler.handleModelCall(new ModelCallException("deepseek 401", null)),
                HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE");
    }

    @Test
    void Agent超时映射504并带进度() {
        ResponseEntity<ErrorBody> response =
                handler.handleTimeout(new ProcessingTimeoutException(60, 3, 2, null));

        assertBody(response, HttpStatus.GATEWAY_TIMEOUT, "AGENT_TIMEOUT");
        assertThat(response.getBody().message()).contains("60").contains("3").contains("2");
    }

    @Test
    void 静态资源不存在映射404而不是500() {
        // 浏览器每打开一个页面都会请求 /favicon.ico；报 500 + ERROR 堆栈属于把噪音演成故障
        ResponseEntity<ErrorBody> response = handler.handleNoResource(
                new NoResourceFoundException(HttpMethod.GET, "favicon.ico"));

        assertBody(response, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
        assertThat(response.getBody().message()).contains("favicon.ico");
    }

    @Test
    void Spring自带的客户端错误不演成500() {
        // MissingServletRequestParameterException 实现 ErrorResponse：4xx 就按 4xx 回
        ResponseEntity<ErrorBody> response = handler.handleGeneric(
                new MissingServletRequestParameterException("q", "String"));

        assertBody(response, HttpStatus.BAD_REQUEST, "BAD_REQUEST");
    }

    @Test
    void 兜底500只说内部错误_细节一个字都不漏() {
        ResponseEntity<ErrorBody> response = handler.handleGeneric(
                new IllegalStateException("jdbc:sqlite:/data/oryxos.db connect failed"));

        assertBody(response, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
        String message = response.getBody().message();
        assertThat(message).isEqualTo(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE);
        assertThat(message)
                .doesNotContain("jdbc:sqlite")
                .doesNotContain("IllegalStateException")
                .doesNotContain("connect failed");
    }

    @Test
    void 错误体只有三个字段() {
        // 多带一个字段，所有对接方的解析代码都要改；契约就得是硬的
        assertThat(ErrorBody.class.getRecordComponents()).hasSize(3);
        assertThat(ErrorBody.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("errorCode", "message", "timestamp");
    }
}
