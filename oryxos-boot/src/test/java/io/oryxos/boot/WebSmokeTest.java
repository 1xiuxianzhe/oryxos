package io.oryxos.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实上下文冒烟：装配没炸、端点可达、管理平台被托管。
 *
 * <p>为什么一定要有一条这样的测试：切片测试（standalone MockMvc）测不到 Bean 扫描、
 * JPA repository 扫描与静态资源打包——这些恰好是"单测全绿、serve 起来就炸"的经典位置
 * （18 节那个 {@code Found 0 repositories} 就是同类问题）。</p>
 *
 * <p>不依赖真实模型：Provider 覆盖成一个指向不可达地址的测试项，
 * 仅验证"配置 → 装配"这条路，不发请求。数据源指到 {@code target/} 下，避免污染工作区。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:target/oryxos-web-smoke.db",
        "oryxos.workspace=target/oryxos-smoke-workspace",
        "oryxos.providers[0].name=smoke-provider",
        "oryxos.providers[0].api-key=smoke-key",
        "oryxos.providers[0].base-url=http://127.0.0.1:9/v1",
        // 30 节：生成与新建模板默认模型。不配置时新建 Agent 会被拒（没有可用 provider 名字）
        "oryxos.author.provider=smoke-provider",
        "oryxos.author.model=smoke-model",
        // 显式钉住沙箱开关：application.yaml 是给人本地调试改的（可能被改成 false），
        // 测试的断言不该跟着环境漂——这里要验的是"配置 true 时 /info 如实反映"
        "oryxos.sandbox.enabled=true",
        "logging.level.io.oryxos=WARN",
        "logging.level.org.hibernate.SQL=WARN"
})
@AutoConfigureMockMvc
class WebSmokeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private MockMvc mockMvc;

    private JsonNode getJson(String path) throws Exception {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody());
    }

    @Test
    void 健康检查真实可达() throws Exception {
        assertThat(getJson("/api/v1/health").path("status").asText()).isEqualTo("UP");
    }

    @Test
    void 系统信息带上了Provider与沙箱摘要() throws Exception {
        JsonNode info = getJson("/api/v1/info");

        assertThat(info.path("name").asText()).isEqualTo("OryxOS");
        assertThat(info.path("version").asText()).isNotBlank();
        assertThat(info.path("canBuildAgents").asBoolean()).isTrue();
        assertThat(info.path("providers").get(0).path("name").asText()).isEqualTo("smoke-provider");
        assertThat(info.path("providers").get(0).path("status").asText()).isEqualTo("configured");
        // 只给开关与条数：没有认证的接口不该把白名单内容摆出来
        assertThat(info.path("sandbox").path("enabled").asBoolean()).isTrue();
        assertThat(info.path("sandbox").has("allowedPaths")).isTrue();
    }

    @Test
    void 端点返回的是真实注册表内容() throws Exception {
        // 工具来自 ToolRegistry：内置工具在装配期登记，证明扫描范围与装配都正常
        JsonNode tools = getJson("/api/v1/tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools.findValuesAsText("name")).contains("read_file", "shell");

        // Profile 来自 ProfileRegistry：测试工作区没有 Profile，返回空数组而不是假清单
        JsonNode profiles = getJson("/api/v1/profiles");
        assertThat(profiles.isArray()).isTrue();

        JsonNode memory = getJson("/api/v1/memory");
        assertThat(memory.has("content")).isTrue();
        assertThat(memory.path("chars").asInt()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 管理平台被真实托管且没有独立后端() throws Exception {
        // /admin 是重定向；静态首页由 Spring 直接托管
        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/index.html"));

        ResponseEntity<String> page = rest.getForEntity("/admin/index.html", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getHeaders().getContentType())
                .isNotNull()
                .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());

        // 管理台是 React 单页应用：index.html 只挂一个 root 容器 + 同源的 /admin/assets/* 产物
        assertThat(page.getBody()).contains("id=\"root\"").contains("/admin/assets/");
        assertThat(page.getBody()).doesNotContain("http://").doesNotContain("https://");

        // 产物真的可下载（base 与 outDir 写错时，index.html 能开但页面一片空白）
        Matcher bundle = Pattern.compile("src=\"(/admin/assets/[^\"]+\\.js)\"").matcher(page.getBody());
        assertThat(bundle.find()).as("index.html 应引用打包后的 JS").isTrue();
        ResponseEntity<String> script = rest.getForEntity(bundle.group(1), String.class);
        assertThat(script.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 不存在并行的第二套前端：旧入口 /admin-react 已下线
        ResponseEntity<String> removedEntry = rest.getForEntity("/admin-react", String.class);
        assertThat(removedEntry.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 未知静态资源返回404而不是500() throws Exception {
        // 正是用户实际踩到的场景：浏览器自动请求 /favicon.ico
        ResponseEntity<String> favicon = rest.getForEntity("/favicon.ico", String.class);
        assertThat(favicon.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(favicon.getBody()).path("errorCode").asText())
                .isEqualTo("RESOURCE_NOT_FOUND");

        // 任意打错的路径同理：客户端错误不该被兜底演成 500
        ResponseEntity<String> typo = rest.getForEntity("/api/v1/nope", String.class);
        assertThat(typo.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void 会话运行明细端点在真实上下文下可达() throws Exception {
        // 1) 未知会话 → 404：证明 /{id}/runs 与 /{id} 的映射不冲突，Service 与三张表仓储都已装配
        ResponseEntity<String> missing = rest.getForEntity("/api/v1/sessions/nope/runs", String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(missing.getBody()).path("errorCode").asText())
                .isEqualTo("SESSION_NOT_FOUND");

        // 2) 新建会话后查明细：空账本（零提问、零 token），不是 500
        String sessionId = createSession("smoke-" + java.util.UUID.randomUUID());

        JsonNode audit = getJson("/api/v1/sessions/" + sessionId + "/runs");
        assertThat(audit.path("sessionId").asText()).isEqualTo(sessionId);
        assertThat(audit.path("totals").path("runs").asInt()).isZero();
        assertThat(audit.path("totals").path("totalTokens").asLong()).isZero();
        assertThat(audit.path("runs").isArray()).isTrue();
    }

    @Test
    void 重复创建会话返回已有会话而不是500() throws Exception {
        // 真实约束下的幂等：sessions 表对 (channel, user_id, profile_name) 唯一，
        // 第二次 POST 若硬插会 SQLITE_CONSTRAINT_UNIQUE → 500；正确行为是返回同一个 id
        String userId = "idem-" + java.util.UUID.randomUUID();

        String first = createSession(userId);
        String second = createSession(userId);

        assertThat(second).isEqualTo(first);

        // 列表里同一身份只有一条会话：重复 POST 不该凭空多出一条
        int matched = 0;
        for (JsonNode session : getJson("/api/v1/sessions")) {
            if (userId.equals(session.path("userId").asText())) {
                matched++;
            }
        }
        assertThat(matched).isEqualTo(1);
    }

    private String createSession(String userId) throws Exception {
        ResponseEntity<String> response = rest.postForEntity("/api/v1/sessions",
                java.util.Map.of("profile", "default", "userId", userId), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("sessionId").asText();
    }

    @Test
    void 未知Agent的无状态调用返回404() throws Exception {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/agents/nope/invoke",
                java.util.Map.of("message", "hi"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(response.getBody()).path("errorCode").asText())
                .isEqualTo("PROFILE_NOT_FOUND");
    }

    /**
     * 30 节的主链路：建 → 立即可见 → 改（走生命周期）→ 删（归档）。
     *
     * <p>这条用例的价值在"真实上下文 + 真实文件系统 + 真实 HTTP"：注册表、调度器、目录三份状态
     * 必须同时改对，切片测试里那种"每个协作者都对、合起来不对"的问题才会暴露。</p>
     */
    @Test
    void Agent管理链路端到端可用() throws Exception {
        String name = "smoke-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        // 1) 建：写脚手架 + 注册；不重启就能查到
        ResponseEntity<String> created = rest.postForEntity("/api/v1/agents",
                java.util.Map.of("name", name, "description", "冒烟用的 Agent"), String.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode detail = objectMapper.readTree(created.getBody());
        assertThat(detail.path("name").asText()).isEqualTo(name);
        assertThat(detail.path("dir").asText()).isEqualTo("agents/" + name);
        assertThat(detail.path("files").toString()).contains("AGENT.md");

        assertThat(getJson("/api/v1/agents").path("agents").findValuesAsText("name")).contains(name);
        assertThat(getJson("/api/v1/profiles").findValuesAsText("name")).contains(name);

        // 2) 工作区：目录可见、AGENT.md 可读
        assertThat(getJson("/api/v1/workspace/tree").toString()).contains("agents/" + name);
        ResponseEntity<String> file = rest.getForEntity(
                "/api/v1/workspace/file?path=agents/" + name + "/AGENT.md", String.class);
        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(file.getBody()).path("content").asText())
                .contains("冒烟用的 Agent");

        // 3) 改：走生命周期（先校验再写盘再重注册）
        String markdown = """
                ---
                description: 冒烟改过
                provider:
                  name: smoke-provider
                  model: smoke-model
                ---

                你是冒烟 Agent。
                """;
        ResponseEntity<String> updated = rest.exchange("/api/v1/agents/" + name, HttpMethod.PUT,
                new HttpEntity<>(java.util.Map.of("markdown", markdown), jsonHeaders()), String.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(updated.getBody()).path("description").asText())
                .isEqualTo("冒烟改过");

        // 4) 删：注销 + 移出索引 + 归档，不物理删除
        ResponseEntity<String> deleted = rest.exchange("/api/v1/agents/" + name, HttpMethod.DELETE,
                HttpEntity.EMPTY, String.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(deleted.getBody()).path("archivedTo").asText())
                .startsWith("archive/" + name);

        ResponseEntity<String> after = rest.getForEntity("/api/v1/agents/" + name, String.class);
        assertThat(after.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static org.springframework.http.HttpHeaders jsonHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
