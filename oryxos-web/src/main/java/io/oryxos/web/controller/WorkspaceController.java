package io.oryxos.web.controller;

import io.oryxos.core.agent.AgentLifecycleService;
import io.oryxos.core.agent.AgentStore;
import io.oryxos.web.dto.WorkspaceDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * WorkspaceController —— 管理台的"文件管理器"：看树、读文件、改文件。
 *
 * <p><b>核心不是浏览，而是防护。</b>这套端点的入参是一个自由路径，天然是目录穿越的入口。
 * 三道拦截缺一不可：</p>
 * <ol>
 *   <li><b>拒绝绝对路径</b>（{@code /etc/passwd}、{@code C:\Windows}）——它们会绕过 {@code resolve}；</li>
 *   <li><b>归一化后必须仍在工作区内</b>（挡 {@code ../../}）；</li>
 *   <li><b>真实路径也要在工作区内</b>（{@code toRealPath()}）——否则一个指向外面的符号链接就能越界。</li>
 * </ol>
 *
 * <p><b>写 AGENT.md 走生命周期语义</b>：它不是"改个文本文件"，而是"改这个 Agent 的定义"，
 * 必须经过校验与重注册。其余文件（脚本、资料）直接原子落盘——它们是 Agent 的资源，不是配置。</p>
 */
@RestController
@RequestMapping("/api/v1/workspace")
public class WorkspaceController {

    /** 树的最大递归深度：再深就变成全盘扫描了。 */
    static final int MAX_DEPTH = 3;

    /** 单目录条目上限：一个大目录不该把响应撑成几 MB。 */
    static final int MAX_ENTRIES = 200;

    /** 单文件读取上限：管理台不是网盘。 */
    static final long MAX_READ_BYTES = 512 * 1024L;

    /** 单文件写入上限（字符）。 */
    static final int MAX_WRITE_CHARS = 512 * 1024;

    /** 不允许在管理台里改写的二进制扩展名（改了就是把数据库写坏）。 */
    private static final Set<String> BINARY_EXTENSIONS =
            Set.of("db", "sqlite", "db-journal", "jar", "class", "png", "jpg", "jpeg", "gif", "zip", "gz");

    private static final Logger logger = LoggerFactory.getLogger(WorkspaceController.class);

    private final AgentStore agentStore;
    private final AgentLifecycleService lifecycle;

    public WorkspaceController(AgentStore agentStore, AgentLifecycleService lifecycle) {
        this.agentStore = agentStore;
        this.lifecycle = lifecycle;
    }

    /** 工作区目录树（深度与单目录条目都有上限，截断是显式声明的）。 */
    @GetMapping("/tree")
    public WorkspaceDtos.TreeView tree() {
        Path root = agentStore.workspaceRoot();
        List<WorkspaceDtos.Node> nodes = Files.isDirectory(root)
                ? children(root, "", 1)
                : List.of();
        return new WorkspaceDtos.TreeView(root.getFileName() == null ? ".oryxos" : root.getFileName().toString(),
                MAX_DEPTH, MAX_ENTRIES, nodes);
    }

    /** 读一个文本文件。 */
    @GetMapping("/file")
    public WorkspaceDtos.FileView read(@RequestParam("path") String path) {
        Path target = resolveSafe(path);
        String relative = relativeOf(target);

        if (!Files.exists(target)) {
            throw new IllegalArgumentException("文件不存在: " + relative);
        }
        if (Files.isDirectory(target)) {
            throw new IllegalArgumentException("这是一个目录，不是文件: " + relative);
        }
        if (isBinary(relative)) {
            throw new IllegalArgumentException("二进制文件不支持在管理台查看: " + relative);
        }

        long size;
        byte[] bytes;
        try {
            size = Files.size(target);
            if (size > MAX_READ_BYTES) {
                throw new IllegalArgumentException(
                        "文件过大（" + size + " 字节 > " + MAX_READ_BYTES + "）: " + relative);
            }
            bytes = Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("读取文件失败: " + e.getMessage(), e);
        }

        return new WorkspaceDtos.FileView(relative, decodeUtf8(bytes, relative), size, true);
    }

    /**
     * 写一个文本文件。
     *
     * <p>{@code agents/<name>/AGENT.md} 走 {@code update}：校验 + 原子写 + 重注册；
     * 其它路径直接原子写。这个分流是这套端点里唯一"看起来特殊"的地方，但它是必须的：
     * 绕过生命周期的写会让磁盘与运行时不一致。</p>
     */
    @PostMapping("/file")
    public WorkspaceDtos.WriteResult write(@RequestBody(required = false) WorkspaceDtos.WriteFile request) {
        if (request == null || request.path() == null || request.path().isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        String content = request.content() == null ? "" : request.content();
        if (content.length() > MAX_WRITE_CHARS) {
            throw new IllegalArgumentException(
                    "内容过大（" + content.length() + " > " + MAX_WRITE_CHARS + " 字符）");
        }

        Path target = resolveSafe(request.path());
        String relative = relativeOf(target);

        if (BINARY_EXTENSIONS.contains(extensionOf(relative))) {
            throw new IllegalArgumentException("不允许在管理台写二进制文件: " + relative);
        }

        String agentName = agentNameOfMainFile(relative);
        if (agentName != null) {
            lifecycle.update(agentName, content);
            logger.info("[Web] 已通过工作区编辑更新 Agent 定义: name={}", agentName);
            return new WorkspaceDtos.WriteResult(relative, content.getBytes(StandardCharsets.UTF_8).length);
        }

        try {
            agentStore.writeAtomically(target, content);
        } catch (IOException e) {
            throw new IllegalStateException("写入文件失败: " + e.getMessage(), e);
        }
        return new WorkspaceDtos.WriteResult(relative, content.getBytes(StandardCharsets.UTF_8).length);
    }

    // ------------------------------------------------------------------
    // 路径防护
    // ------------------------------------------------------------------

    /**
     * 把"相对工作区的路径"解析成绝对路径，越界即 400。
     *
     * @throws IllegalArgumentException 空路径、绝对路径、含 {@code ..}、真实路径逃出工作区
     */
    Path resolveSafe(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        String relative = rawPath.replace('\\', '/').strip();
        if (relative.startsWith("/") || relative.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("path 必须是相对工作区的路径: " + rawPath);
        }
        for (String segment : relative.split("/")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException("path 不允许出现 ..: " + rawPath);
            }
        }

        Path root = agentStore.workspaceRoot();
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw new IllegalArgumentException("path 越界: " + rawPath);
        }

        // 真实路径校验：拦"工作区内的符号链接指向工作区外"这种绕过
        Path probe = candidate;
        while (probe != null && !Files.exists(probe)) {
            probe = probe.getParent();
        }
        if (probe != null) {
            try {
                if (!probe.toRealPath().startsWith(root.toRealPath())) {
                    throw new IllegalArgumentException("path 越界（真实路径不在工作区内）: " + rawPath);
                }
            } catch (IOException e) {
                throw new IllegalStateException("解析路径失败: " + e.getMessage(), e);
            }
        }
        return candidate;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private List<WorkspaceDtos.Node> children(Path dir, String prefix, int depth) {
        if (depth > MAX_DEPTH) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    // 目录在前、同类按名字排序：前端每次刷新的顺序必须稳定
                    .sorted(Comparator.comparing((Path path) -> !Files.isDirectory(path))
                            .thenComparing(path -> path.getFileName().toString()))
                    .limit(MAX_ENTRIES)
                    .map(path -> node(path, prefix, depth))
                    .toList();
        } catch (IOException e) {
            logger.warn("[Web] 列出目录失败: dir={}, reason={}", dir, e.getMessage());
            return List.of();
        }
    }

    private WorkspaceDtos.Node node(Path path, String prefix, int depth) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        String relative = prefix.isEmpty() ? name : prefix + "/" + name;
        boolean directory = Files.isDirectory(path);
        long size = 0L;
        if (!directory) {
            try {
                size = Files.size(path);
            } catch (IOException e) {
                size = 0L;
            }
        }
        List<WorkspaceDtos.Node> children = directory
                ? children(path, relative, depth + 1)
                : List.of();
        return new WorkspaceDtos.Node(name, relative, directory ? "dir" : "file", size, children);
    }

    private String relativeOf(Path target) {
        return agentStore.workspaceRoot().relativize(target).toString().replace('\\', '/');
    }

    /** {@code agents/<name>/AGENT.md} → {@code <name>}；其它路径返回 {@code null}。 */
    private static String agentNameOfMainFile(String relative) {
        String[] segments = relative.split("/");
        if (segments.length != 3) {
            return null;
        }
        if (!"agents".equals(segments[0]) || !"AGENT.md".equals(segments[2])) {
            return null;
        }
        return segments[1];
    }

    private static String extensionOf(String relative) {
        int dot = relative.lastIndexOf('.');
        return dot < 0 ? "" : relative.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isBinary(String relative) {
        return BINARY_EXTENSIONS.contains(extensionOf(relative));
    }

    /**
     * 严格 UTF-8 解码：非法字节序列直接报 400。
     *
     * <p>用替换字符糊过去会让用户看到一堆 {@code ?}，然后真的把乱码保存回去——
     * 那才是数据损坏。NUL 字节视为二进制（文本文件里不该出现）。</p>
     */
    private static String decodeUtf8(byte[] bytes, String relative) {
        for (byte b : bytes) {
            if (b == 0) {
                throw new IllegalArgumentException("这不是文本文件（包含 NUL 字节）: " + relative);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("文件不是合法 UTF-8 文本: " + relative);
        }
    }
}
