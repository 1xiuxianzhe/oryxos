package io.oryxos.core.react;

import io.oryxos.core.context.ContextLoader;
import io.oryxos.core.llm.LlmRequest;
import io.oryxos.core.memory.MemoryScope;
import io.oryxos.core.memory.MemoryService;
import io.oryxos.core.profile.Profile;
import io.oryxos.core.session.Session;
import io.oryxos.core.tool.OryxTool;
import io.oryxos.core.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PromptBuilder 验收测试：四部分顺序、历史按"完整往返"截断、system prompt 末尾含当前日期时间。
 */
class PromptBuilderTest {

    @TempDir
    Path workspace;

    private PromptBuilder builder(MemoryService memoryService) {
        return new PromptBuilder(new ContextLoader(workspace), memoryService);
    }

    /** 假门面：只验证"第二部分 = 门面输出的长期记忆"，不碰文件。 */
    private static MemoryService memoryService(String longTermMemory) {
        return new MemoryService() {
            @Override
            public String buildContext(Session session) {
                return longTermMemory;
            }

            @Override
            public String snapshot() {
                return longTermMemory;
            }

            @Override
            public String snapshot(java.nio.file.Path agentDir) {
                return longTermMemory;
            }

            @Override
            public void remember(String content, MemoryScope scope) {
            }

            @Override
            public List<String> recall(String keyword) {
                return List.of();
            }
        };
    }

    private Profile profile(int maxHistoryTurns) {
        Profile profile = new Profile();
        profile.setName("ops-agent");
        profile.setIdentity("你是运维助手");
        profile.setSettings(Map.of("max_history_turns", maxHistoryTurns));
        return profile;
    }

    private Session sessionWithTurns(int turns) {
        Session session = new Session("s-1", "ops-agent", "cli", "u-1");
        for (int i = 1; i <= turns; i++) {
            session.addMessage(new Session.Message("user", "问题" + i));
            session.addMessage(new Session.Message("assistant", "回答" + i));
        }
        return session;
    }

    private OryxTool dummyTool() {
        return new OryxTool() {
            @Override
            public String getName() {
                return "http_get";
            }

            @Override
            public String getDescription() {
                return "发起 HTTP GET 请求";
            }

            @Override
            public String getInputSchema() {
                return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}}}";
            }

            @Override
            public ToolResult execute(Map<String, Object> input) {
                return ToolResult.ok("ok");
            }
        };
    }

    @Test
    void 四部分顺序正确_记忆独立于system_prompt_且工具随请求传递() throws IOException {
        Files.writeString(workspace.resolve("AGENTS.md"), "项目背景说明");
        Files.writeString(workspace.resolve("SOUL.md"), "人格设定说明");
        Profile profile = new Profile();
        profile.setName("ops-agent");
        profile.setIdentity("你是运维助手");
        Profile.BootstrapConfig bootstrap = new Profile.BootstrapConfig();
        bootstrap.setFiles(List.of("AGENTS.md", "SOUL.md"));
        profile.setBootstrap(bootstrap);
        profile.setSkills(List.of(inlineSkill("pr-digest", "技能说明")));

        LlmRequest request = builder(memoryService("用户偏好：用中文回答"))
                .build(sessionWithTurns(1), profile, List.of(dummyTool()));

        String systemPrompt = request.systemPrompt();
        // identity → bootstrap → skill 的顺序
        assertThat(systemPrompt.indexOf("你是运维助手")).isLessThan(systemPrompt.indexOf("项目背景说明"));
        assertThat(systemPrompt.indexOf("项目背景说明")).isLessThan(systemPrompt.indexOf("人格设定说明"));
        assertThat(systemPrompt.indexOf("人格设定说明")).isLessThan(systemPrompt.indexOf("技能说明"));

        // 长期记忆是独立部分，不混进 system prompt
        assertThat(request.memory()).isEqualTo("用户偏好：用中文回答");
        assertThat(systemPrompt).doesNotContain("用户偏好");

        // 工具 schema 随请求单独传递
        assertThat(request.tools()).hasSize(1);
        assertThat(request.tools().get(0).name()).isEqualTo("http_get");
    }

    @Test
    void system_prompt末尾含当前日期时间() {
        LlmRequest request = builder(null).build(sessionWithTurns(1), profile(20), List.of());

        String systemPrompt = request.systemPrompt().trim();
        // 必须收尾于"当前时间：<时间戳>"——模型自己不知道"今天"是几号，全靠这一行
        assertThat(systemPrompt).containsPattern("当前时间：\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");

        Matcher matcher = Pattern.compile("当前时间：.*$", Pattern.DOTALL).matcher(systemPrompt);
        assertThat(matcher.find()).isTrue();
        assertThat(systemPrompt).endsWith(matcher.group());
    }

    @Test
    void 历史超过N次往返被截断_保留最近N段() {
        Session session = sessionWithTurns(30);

        List<Map<String, String>> history = builder(null).buildHistory(session, 20);

        assertThat(history).hasSize(40);
        assertThat(history.get(0).get("role")).isEqualTo("user");
        assertThat(history.get(0).get("content")).isEqualTo("问题11");
        assertThat(history.get(history.size() - 1).get("content")).isEqualTo("回答30");
    }

    @Test
    void 未超过上限时完整保留() {
        Session session = sessionWithTurns(3);

        assertThat(builder(null).buildHistory(session, 20)).hasSize(6);
    }

    @Test
    void 含工具消息的往返不被拦腰截断() {
        Session session = new Session("s-1", "ops-agent", "cli", "u-1");
        for (int i = 1; i <= 25; i++) {
            session.addMessage(new Session.Message("user", "问题" + i));
            session.addMessage(new Session.Message("assistant", "调一下工具"));
            session.addMessage(new Session.Message("tool", "[Tool Result] 结果" + i));
            session.addMessage(new Session.Message("assistant", "回答" + i));
        }

        List<Map<String, String>> history = builder(null).buildHistory(session, 20);

        // 20 次往返 × 4 条消息，且以 user 开头，工具消息留在其所属往返内
        assertThat(history).hasSize(80);
        assertThat(history.get(0).get("role")).isEqualTo("user");
        assertThat(history.get(0).get("content")).isEqualTo("问题6");
        assertThat(history.stream().filter(m -> "tool".equals(m.get("role"))).count()).isEqualTo(20);
    }

    @Test
    void 无记忆实现时记忆为空() {
        LlmRequest request = builder(null).build(sessionWithTurns(1), profile(20), List.of());

        assertThat(request.memory()).isEmpty();
        assertThat(request.hasMemory()).isFalse();
    }

    @Test
    void 长期记忆与会话历史各注入一次_不重复() {
        Session session = new Session("s-1", "ops-agent", "cli", "u-1");
        session.addMessage(new Session.Message("user", "会话历史标记"));
        session.addMessage(new Session.Message("assistant", "回答"));

        LlmRequest request = builder(memoryService("长期记忆标记")).build(session, profile(20), List.of());

        // 第二部分只放长期记忆——会话历史不在这里重复出现
        assertThat(request.memory()).isEqualTo("长期记忆标记").doesNotContain("会话历史标记");
        // 会话历史仍由第三部分承担，且只出现一次
        String historyText = request.history().toString();
        assertThat(historyText).contains("会话历史标记").doesNotContain("长期记忆标记");
        assertThat(countOccurrences(historyText, "会话历史标记")).isEqualTo(1);
        assertThat(request.systemPrompt())
                .doesNotContain("长期记忆标记")
                .doesNotContain("会话历史标记");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private Profile.SkillConfig inlineSkill(String name, String prompt) {
        Profile.SkillConfig skill = new Profile.SkillConfig();
        skill.setName(name);
        skill.setPrompt(prompt);
        return skill;
    }
}
