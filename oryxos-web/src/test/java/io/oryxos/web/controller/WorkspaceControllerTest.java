package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentStore;
import io.oryxos.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作区端点：<b>防护是主线</b>。
 *
 * <p>四种越界形态（相对穿越、绝对路径、盘符路径、非 UTF-8/二进制）必须全部 400，
 * 且都要给出可读原因——只说"禁止访问"会让用户以为是自己权限不够。</p>
 */
class WorkspaceControllerTest {

    @TempDir
    Path workspace;

    private final AgentLifecycleService lifecycle = mock(AgentLifecycleService.class);
    private AgentStore store;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        store = new AgentStore(workspace, "deepseek", "deepseek-flash");
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkspaceController(store, lifecycle))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private Path writeAgent(String name, String content) throws Exception {
        Path dir = store.createScaffold(name, "演示");
        Files.writeString(dir.resolve("AGENT.md"), content, StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    void 目录树按相对路径返回() throws Exception {
        writeAgent("demo", "---\ndescription: x\nprovider:\n  name: deepseek\n---\n正文");

        mockMvc.perform(get("/api/v1/workspace/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxDepth").value(3))
                .andExpect(jsonPath("$.nodes[0].name").value("agents"))
                .andExpect(jsonPath("$.nodes[0].type").value("dir"))
                .andExpect(jsonPath("$.nodes[0].children[0].name").value("demo"))
                .andExpect(jsonPath("$.nodes[0].children[0].path").value("agents/demo"));
    }

    @Test
    void 读取文本文件成功() throws Exception {
        writeAgent("demo", "# 定义\n正文");

        mockMvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/AGENT.md"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("agents/demo/AGENT.md"))
                .andExpect(jsonPath("$.content").value("# 定义\n正文"))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    void 相对路径穿越被拒() throws Exception {
        mockMvc.perform(get("/api/v1/workspace/file").param("path", "../../etc/passwd"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("..")));
    }

    @Test
    void 绝对路径与盘符路径被拒() throws Exception {
        mockMvc.perform(get("/api/v1/workspace/file").param("path", "/etc/passwd"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("相对工作区")));

        mockMvc.perform(get("/api/v1/workspace/file").param("path", "C:/Windows/win.ini"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("相对工作区")));
    }

    @Test
    void 文件不存在返回可读的400() throws Exception {
        mockMvc.perform(get("/api/v1/workspace/file").param("path", "agents/ghost/AGENT.md"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不存在")));
    }

    @Test
    void 目录不能当文件读() throws Exception {
        writeAgent("demo", "正文");

        mockMvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("目录")));
    }

    @Test
    void 二进制文件被拒() throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("agents/demo"));
        Files.write(dir.resolve("blob.bin"), new byte[]{1, 0, 2, 3});

        mockMvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/blob.bin"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("NUL")));
    }

    @Test
    void 非UTF8文本被拒() throws Exception {
        Path dir = Files.createDirectories(workspace.resolve("agents/demo"));
        Path file = dir.resolve("gbk.txt");
        Files.write(file, new byte[]{(byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4});

        mockMvc.perform(get("/api/v1/workspace/file").param("path", "agents/demo/gbk.txt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("UTF-8")));
    }

    @Test
    void 写普通文件直接落盘() throws Exception {
        writeAgent("demo", "正文");

        mockMvc.perform(post("/api/v1/workspace/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"agents/demo/REFERENCE.md\",\"content\":\"# 资料\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("agents/demo/REFERENCE.md"));

        assertThat(Files.readString(workspace.resolve("agents/demo/REFERENCE.md"), StandardCharsets.UTF_8))
                .isEqualTo("# 资料");
    }

    @Test
    void 写AGENT主文件走生命周期语义() throws Exception {
        writeAgent("demo", "正文");

        mockMvc.perform(post("/api/v1/workspace/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"agents/demo/AGENT.md\",\"content\":\"---\\ndescription: 新\\n---\\n新正文\"}"))
                .andExpect(status().isOk());

        verify(lifecycle).update(eq("demo"), eq("---\ndescription: 新\n---\n新正文"));
    }

    @Test
    void 不允许写数据库文件() throws Exception {
        mockMvc.perform(post("/api/v1/workspace/file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"oryxos.db\",\"content\":\"oops\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("二进制")));
    }

    @Test
    void 工作区目录不存在时树返回空列表而不是500() throws Exception {
        AgentStore missing = new AgentStore(workspace.resolve("not-created"), null, null);
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new WorkspaceController(missing, lifecycle))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/workspace/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").isEmpty());
    }
}
