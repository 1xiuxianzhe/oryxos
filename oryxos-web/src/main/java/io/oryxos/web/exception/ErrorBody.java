package io.oryxos.web.exception;

/**
 * 统一错误响应体：{@code errorCode} + {@code message} + {@code timestamp}。
 *
 * <p>用 record 而不是 {@code Map.of}：字段名与类型在编译期固定，springdoc 能据此生成 schema，
 * 也避免 {@code Map.of} 不接受 null 值带来的隐患。</p>
 *
 * <p><b>三字段是硬约束</b>：不追加 {@code path} / {@code trace} / {@code exception}。
 * 调用方只需要写一次解析逻辑；任何"偶尔多带一个字段"都会让对接方写防御性代码。</p>
 *
 * @param errorCode 稳定的错误码（见 {@link GlobalExceptionHandler}）
 * @param message   面向调用方的可读说明；500 固定为"内部错误"
 * @param timestamp ISO-8601 时间戳
 */
public record ErrorBody(String errorCode, String message, String timestamp) {
}
