package io.oryxos.core.profile;

/**
 * Profile 校验失败异常。
 * 用于表达 Profile 配置本身不合法（如引用了全局层不存在的 provider），
 * 消息必须足够清晰，方便定位是哪个 Profile 的哪个字段出了问题。
 */
public class ProfileValidationException extends RuntimeException {

    public ProfileValidationException(String message) {
        super(message);
    }

    public ProfileValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
