package io.oryxos;

import io.oryxos.core.profile.Profile;
import io.oryxos.core.profile.ProfileLoader;
import io.oryxos.provider.LlmResponse;
import io.oryxos.provider.Prompt;
import io.oryxos.provider.ProviderService;
import io.oryxos.storage.entity.LlmCallEntity;
import io.oryxos.storage.repository.LlmCallRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实调用一次大模型：向 AI 提问「1+1等于多少」，sessionId = 1。
 *
 * <p>用的是配置文件里的真实配置：</p>
 * <ul>
 *   <li>全局层：{@code application.yaml} 的 {@code oryxos.providers}（provider 名 / base-url / api-key）</li>
 *   <li>Profile 层：{@code .oryxos/profiles/default.yaml} 的 {@code provider.model}</li>
 * </ul>
 *
 * <p>这是集成测试（真实网络调用、会产生费用），类名以 {@code IT} 结尾，
 * {@code mvn test} 默认不会执行；需要手动运行：</p>
 * <pre>
 * # 方式一（IDE）：直接点这个类的运行按钮
 * # 方式二（Maven）：
 * mvn -pl oryxos-boot -am test -Dtest=AskAiIT -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        // 测试用独立的 SQLite 文件，避免污染 .oryxos/oryxos.db
        "spring.datasource.url=jdbc:sqlite:target/oryxos-ask-ai.db",
        // 让 ProfileLoader 找到仓库根目录下的 .oryxos/profiles
        // （运行测试时 user.dir 是 oryxos-boot 模块目录，故用 ../.oryxos）
        "oryxos.workspace=../.oryxos"
})
class AskAiIT {

    @Autowired
    private ProviderService providerService;

    @Autowired
    private ProfileLoader profileLoader;

    @Autowired
    private LlmCallRepository llmCallRepository;

    @Test
    void 问AI_1加1等于多少() {
        Profile profile = profileLoader.get("default");
        assertThat(profile)
                .as("Profile [default] 未加载成功，请检查 .oryxos/profiles/default.yaml")
                .isNotNull();
        assertThat(profile.getProvider()).as("Profile 缺少 provider 段").isNotNull();

        System.out.println("========== Ask AI ==========");
        System.out.println("sessionId = 1");
        System.out.println("provider  = " + profile.getProvider().getName());
        System.out.println("model     = " + profile.getProvider().getModel());
        System.out.println("question  = 1+1等于多少");

        LlmResponse response = providerService.chat("1", profile, Prompt.ofUserMessage("1+1等于多少"));

        System.out.println("answer    = " + response.text());
        System.out.println("toolCalls = " + response.toolCalls());
        System.out.println("usage     = " + response.usage());
        System.out.println("=============================");

        assertThat(response.text()).as("模型回答不能为空").isNotBlank();

        // 审计：成功调用应落一条 llm_calls（session_id = 1）
        List<LlmCallEntity> calls = llmCallRepository.findBySessionId("1");
        assertThat(calls).as("llm_calls 应记录本次调用").isNotEmpty();
        assertThat(calls).anyMatch(call -> Boolean.TRUE.equals(call.getSuccess()));
    }
}
