package io.oryxos.cli;

/**
 * Profile 模板生成。
 *
 * <p>模板必须与 {@code io.oryxos.core.profile.Profile} 的结构一致：{@code provider} 是对象、
 * {@code channels} 是对象列表，否则 ProfileLoader 会解析失败并跳过该 Profile。</p>
 */
final class ProfileTemplates {

    /** 全局层（application.yaml 的 oryxos.providers）声明的默认 provider 名。 */
    private static final String DEFAULT_PROVIDER = "deepseek";

    private static final String DEFAULT_MODEL = "deepseek-chat";

    private ProfileTemplates() {
    }

    static String defaultProfile(String name, String description) {
        return """
                name: %s
                description: %s
                identity: 你是 OryxOS 上的助手 %s，负责帮助用户完成日常任务。
                provider:
                  name: %s
                  model: %s
                  temperature: 0.7
                tools: []
                channels:
                  - type: cli
                # 出站通知（可选）：群机器人 webhook 地址。
                # 用环境变量占位，不要把地址明文写进来——地址本身通常内嵌凭证。
                # notify_channels:
                #   - type: webhook
                #     config:
                #       url: ${TEAM_WEBHOOK_URL}
                settings:
                  max_iterations: 10
                  max_history_turns: 20
                """.formatted(name, description, name, DEFAULT_PROVIDER, DEFAULT_MODEL);
    }
}
