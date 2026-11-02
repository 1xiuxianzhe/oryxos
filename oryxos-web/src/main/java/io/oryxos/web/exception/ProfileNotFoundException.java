package io.oryxos.web.exception;

/**
 * 请求的 Profile（Agent 名）不存在。
 *
 * <p>与 {@link SessionNotFoundException} 同一取向：不"顺手"用该名字现造一个 Agent，
 * 拼错名字的人应该在第一次调用时就收到 404，而不是拿到一个用默认配置跑出来的奇怪回复。</p>
 */
public class ProfileNotFoundException extends RuntimeException {

    private final String profileName;

    public ProfileNotFoundException(String profileName) {
        super("Agent/Profile 不存在: '" + profileName + "'。可用 GET /api/v1/profiles 查看当前注册的 Agent");
        this.profileName = profileName;
    }

    public String getProfileName() {
        return profileName;
    }
}
