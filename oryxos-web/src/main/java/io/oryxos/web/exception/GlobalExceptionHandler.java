package io.oryxos.web.exception;

import io.oryxos.core.agent.AgentConfigurationException;
import io.oryxos.core.agent.AgentGenerationException;
import io.oryxos.core.agent.AgentNotFoundException;
import io.oryxos.core.profile.AgentDefinitionException;
import io.oryxos.core.react.ModelCallException;
import io.oryxos.core.react.ProcessingTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;

/**
 * GlobalExceptionHandler —— 唯一错误出口。
 *
 * <p>所有失败响应都是 {@link ErrorBody} 三字段；状态码语义固定：
 * 400 参数/状态冲突、404 资源不存在、500 内部错误、503 Provider 故障、504 Agent 超时。</p>
 *
 * <p><b>兜底那条刻意不透传 {@code e.getMessage()}</b>：内部异常消息里经常带着连接串、
 * SQL、文件路径、类名，这些属于"内幕"，只进日志。对外统一说"内部错误"——
 * 这是门面该有的分寸，也是本节修复的一个既有缺陷。</p>
 *
 * <p>日志带 MDC 里的 {@code runId}（由 {@code AgentService} 注入），
 * 可直接与 {@code agent_runs} 表那条记录对照排查。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 500 的固定对外话术：不随异常内容变化。 */
    public static final String INTERNAL_ERROR_MESSAGE = "内部错误";

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数错误：消息为空、超出长度上限等由控制器直接抛出的非法参数。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleBadRequest(IllegalArgumentException e) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage());
    }

    /**
     * 请求体不是合法 JSON —— 属调用方错误，必须返回 400 而不是 500。
     *
     * <p>报成 500 的代价不只是状态码不准：{@code INTERNAL_ERROR} 会把人往"服务端崩了"的方向带，
     * 而真实原因可能只是一个没转义的反斜杠。</p>
     *
     * <p>最常见的触发场景是 Windows 路径：JSON 字符串里 {@code \} 是转义符，
     * {@code "G:\code\ORYXOS"} 中的 {@code \c} 不是合法转义。所以这里直接把修法写进错误信息——
     * 这条路径（文件工具 + Windows）在本产品里会反复出现，值得给出可照抄的答案。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadableBody(HttpMessageNotReadableException e) {
        String cause = e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage();
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST_BODY",
                "请求体不是合法 JSON：" + cause
                        + "（提示：JSON 字符串里的反斜杠是转义符，Windows 路径请写成双反斜杠"
                        + " 或改用正斜杠，例如 \"G:/code/ORYXOS\"）");
    }

    /** 显式传入的 sessionId 不存在。 */
    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorBody> handleSessionNotFound(SessionNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", e.getMessage());
    }

    /** 会话已归档：资源在，但状态不允许写。 */
    @ExceptionHandler(SessionArchivedException.class)
    public ResponseEntity<ErrorBody> handleSessionArchived(SessionArchivedException e) {
        return build(HttpStatus.BAD_REQUEST, "SESSION_ARCHIVED", e.getMessage());
    }

    /** Agent / Profile 不存在。 */
    @ExceptionHandler(ProfileNotFoundException.class)
    public ResponseEntity<ErrorBody> handleProfileNotFound(ProfileNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", e.getMessage());
    }

    /** 请求的 Profile 存在但不可用（配置缺陷）——属于调用方的参数问题，不是服务端故障。 */
    @ExceptionHandler(AgentConfigurationException.class)
    public ResponseEntity<ErrorBody> handleAgentConfig(AgentConfigurationException e) {
        return build(HttpStatus.BAD_REQUEST, "AGENT_CONFIG_ERROR", e.getMessage());
    }

    /**
     * 要操作的 Agent 不存在：目录与注册表都没有。
     *
     * <p>与 {@link ProfileNotFoundException} 分开报码（{@code AGENT_NOT_FOUND} vs
     * {@code PROFILE_NOT_FOUND}）：前者是"管理台要改的那个东西不存在"，
     * 后者是"业务请求指定的 Profile 不存在"，前端据此决定是刷新列表还是提示改名字。</p>
     */
    @ExceptionHandler(AgentNotFoundException.class)
    public ResponseEntity<ErrorBody> handleAgentNotFound(AgentNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", e.getMessage());
    }

    /**
     * Agent 定义本身不合法（缺 AGENT.md、frontmatter 非法、provider 未声明）。
     *
     * <p>这是<b>调用方的配置问题</b>，不是服务端故障：返回 400 并原样给出可读原因，
     * 用户照着改就能过。做成 500 会让"写错一个字段"看起来像"系统崩了"。</p>
     */
    @ExceptionHandler(AgentDefinitionException.class)
    public ResponseEntity<ErrorBody> handleAgentDefinition(AgentDefinitionException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_AGENT_DEFINITION", e.getMessage());
    }

    /** 生成能力：配置缺失/模型不可用 → 503；生成结果不能用 → 400。 */
    @ExceptionHandler(AgentGenerationException.class)
    public ResponseEntity<ErrorBody> handleGeneration(AgentGenerationException e) {
        if (e.unavailable()) {
            return build(HttpStatus.SERVICE_UNAVAILABLE, "GENERATION_UNAVAILABLE", e.getMessage());
        }
        return build(HttpStatus.BAD_REQUEST, "GENERATION_FAILED", e.getMessage());
    }

    /** Agent 处理超时：附上已完成进度，便于调用方判断"值不值得重试"。 */
    @ExceptionHandler(ProcessingTimeoutException.class)
    public ResponseEntity<ErrorBody> handleTimeout(ProcessingTimeoutException e) {
        return build(HttpStatus.GATEWAY_TIMEOUT, "AGENT_TIMEOUT",
                "Agent 处理超时（上限 " + e.getTimeoutSeconds() + " 秒），已完成 "
                        + e.getCompletedIterations() + " 轮、" + e.getToolCallCount() + " 次工具调用");
    }

    /** 模型侧失败（网络、限流、鉴权、错误响应）。核心阶段不兜底、不切换模型，如实上报。 */
    @ExceptionHandler(ModelCallException.class)
    public ResponseEntity<ErrorBody> handleModelCall(ModelCallException e) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_UNAVAILABLE", e.getMessage());
    }

    /**
     * 静态资源不存在。
     *
     * <p>Spring 6.1 起，路径没匹配到任何处理器（包括浏览器自动请求的 {@code /favicon.ico}）
     * 抛的是 {@link NoResourceFoundException}。若不显式接住，它会落到下面的兜底 →
     * 返回 500 并打一条 ERROR 全堆栈：把"路径打错了"演成"服务端崩了"，
     * 而且每打开一次页面就刷一条无害噪音。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorBody> handleNoResource(NoResourceFoundException e) {
        // 客户端搞错路径，不是故障：debug 足够，不能占 ERROR 日志
        String path = e.getResourcePath();
        logger.debug("[Web] 资源不存在: {}", path);
        return build(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
                "资源不存在: " + (path != null && path.startsWith("/") ? path : "/" + path));
    }

    /** 方法不支持（例如对只读端点发 POST）：调用方问题，405 而不是 500。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorBody> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "请求方法不支持，可用方法: " + e.getSupportedHttpMethods());
    }

    /** Content-Type 不支持：415 而不是 500。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorBody> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "请求体类型不支持，可用类型: " + e.getSupportedMediaTypes());
    }

    /**
     * 兜底：未被上面识别的异常一律 500。
     *
     * <p>细节只进日志（含完整堆栈与 runId），对外只给固定话术。</p>
     *
     * <p>唯一的例外是 Spring MVC 自带的客户端错误（400/404/405/406/415 等）：它们都实现了
     * {@link ErrorResponse}，按自己的状态码回、只记 debug。把 4xx 一律演成 500 的代价是
     * 调用方按"服务端故障"去重试，运维按"服务端故障"去查日志——两头都被带偏。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> handleGeneric(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode statusCode = errorResponse.getStatusCode();
            if (statusCode.is4xxClientError()) {
                HttpStatus resolved = HttpStatus.resolve(statusCode.value());
                String code = resolved != null ? resolved.name() : "CLIENT_ERROR";
                String message = e.getMessage() == null ? statusCode.toString() : e.getMessage();
                logger.debug("[Web] 客户端错误 {}: {}", statusCode.value(), message);
                return build(statusCode, code, message);
            }
        }

        logger.error("[Web] 未处理异常（对外只返回固定话术）runId={}", MDC.get("runId"), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", INTERNAL_ERROR_MESSAGE);
    }

    private static ResponseEntity<ErrorBody> build(HttpStatusCode status, String errorCode, String message) {
        return ResponseEntity.status(status)
                .body(new ErrorBody(errorCode, message == null ? "" : message, Instant.now().toString()));
    }
}
