package io.oryxos.cli;

import io.oryxos.memory.MemoryFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

final class WorkspaceLayout {
    static final String WORKSPACE_PROPERTY = "oryxos.workspace";
    private static final String ROOT = ".oryxos";
    private static final String PROFILE_SUFFIX = ".yaml";
    private static final String DEFAULT_PROFILE = "default";

    private static final String DEFAULT_AGENTS = """
            # AGENTS.md

            项目级 Agent 行为说明。描述当前项目的背景、约定与 Agent 需要遵守的规则。
            """;

    private static final String DEFAULT_SOUL = """
            # SOUL.md

            Agent 人格定义。描述 Agent 的身份、语气与行为风格。
            """;

    private static final String DEFAULT_USER = """
            # USER.md

            用户偏好。描述用户的习惯、技术栈偏好与沟通方式。
            """;

    private final Path baseDirectory;
    private final Path root;

    private WorkspaceLayout(Path baseDirectory) {
        this.baseDirectory = baseDirectory.toAbsolutePath().normalize();
        this.root = this.baseDirectory.resolve(ROOT);
    }

    static WorkspaceLayout current() {
        String configured = System.getProperty(WORKSPACE_PROPERTY);
        return new WorkspaceLayout(configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir")) : Path.of(configured));
    }

    Path root() {
        return root;
    }

    Path profiles() {
        return root.resolve("profiles");
    }

    Path providers() {
        return root.resolve("providers");
    }

    Path tools() {
        return root.resolve("tools");
    }

    Path sessions() {
        return root.resolve("oryxos.db");
    }

    /** 长期记忆文件：{@code <workspace>/memory/MEMORY.md}（与运行时共用 {@link MemoryFiles} 的约定）。 */
    Path memoryFile() {
        return MemoryFiles.resolve(root);
    }

    /** Profile 文件路径；与 ProfileLoader 支持的 {@code .yaml} 保持一致。 */
    Path profileFile(String name) {
        return profiles().resolve(name + PROFILE_SUFFIX);
    }

    boolean initialized() {
        return Files.isDirectory(root) && Files.isDirectory(profiles())
                && Files.exists(profileFile(DEFAULT_PROFILE));
    }

    void requireInitialized() {
        if (!initialized()) {
            throw new WorkspaceException("工作区未初始化: " + root + "，请先执行 oryxos init");
        }
    }

    void initialize() throws IOException {
        Files.createDirectories(root);
        for (String directory : List.of("profiles", "sessions", "skills", "logs", "tools", "memory")) {
            Files.createDirectories(root.resolve(directory));
        }
        // Bootstrap 三件套由 ContextLoader 从工作区根目录读取，缺失只会 WARN，这里提供可编辑模板。
        createIfMissing(root.resolve("AGENTS.md"), DEFAULT_AGENTS);
        createIfMissing(root.resolve("SOUL.md"), DEFAULT_SOUL);
        createIfMissing(root.resolve("USER.md"), DEFAULT_USER);
        createIfMissing(root.resolve("mcp_servers.yaml"), "servers: []\n");
        createIfMissing(profileFile(DEFAULT_PROFILE),
                ProfileTemplates.defaultProfile(DEFAULT_PROFILE, "默认 Profile，开箱即用"));

        // 长期记忆放规范位置 memory/MEMORY.md（路径与骨架同运行时共用一份约定），
        // 并把旧位置（工作区根）的遗留文件迁移过来——否则两个 MEMORY.md 会长期互相误导。
        MemoryFiles.initialize(root);
        MemoryFiles.migrateLegacy(root);
    }

    private static void createIfMissing(Path file, String content) throws IOException {
        if (Files.notExists(file)) {
            Files.writeString(file, content, StandardOpenOption.CREATE_NEW);
        }
    }

    static final class WorkspaceException extends RuntimeException {
        WorkspaceException(String message) {
            super(Objects.requireNonNull(message));
        }
    }
}
