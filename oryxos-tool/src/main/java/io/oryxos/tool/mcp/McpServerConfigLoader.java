package io.oryxos.tool.mcp;

import io.oryxos.core.profile.ProfileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 读取工作区的外部工具服务声明文件（{@code .oryxos/mcp_servers.yaml}）。
 *
 * <p>这是连接信息的<b>唯一真相来源</b>；Agent 配置里只按名字引用，避免同一份连接参数在多个地方各写一遍。</p>
 *
 * <p>容错策略：文件缺失、内容为空、单条声明非法都只告警并跳过，<b>不阻断启动</b>——
 * 外部依赖的可用性与配置正确性都不应变成自身起不来的理由。</p>
 */
public class McpServerConfigLoader {

    private static final Logger logger = LoggerFactory.getLogger(McpServerConfigLoader.class);

    /** 声明文件在工作区中的文件名。 */
    public static final String FILE_NAME = "mcp_servers.yaml";

    private static final String ROOT_KEY = "servers";

    private final Path configFile;

    private final Yaml yaml = new Yaml();

    public McpServerConfigLoader(Path workspaceDir) {
        this.configFile = workspaceDir == null ? null : workspaceDir.resolve(FILE_NAME);
    }

    /**
     * 读取全部合法声明。
     *
     * @return 合法的服务声明；文件缺失或无可解析内容时返回空列表
     */
    @SuppressWarnings("unchecked")
    public List<McpServerDeclaration> load() {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            logger.debug("[Mcp] 未找到外部服务声明文件，外部工具数为 0: {}", configFile);
            return List.of();
        }

        Map<String, Object> root;
        try {
            Object loaded = yaml.load(Files.readString(configFile, StandardCharsets.UTF_8));
            if (loaded == null) {
                return List.of();
            }
            if (!(loaded instanceof Map<?, ?> map)) {
                logger.warn("[Mcp] 声明文件根节点必须是映射，实际为 {}，已忽略: {}",
                        loaded.getClass().getSimpleName(), configFile);
                return List.of();
            }
            root = (Map<String, Object>) map;
        } catch (Exception e) {
            logger.warn("[Mcp] 声明文件解析失败，已忽略: file={}, error={}", configFile, e.getMessage());
            return List.of();
        }

        Object servers = root.get(ROOT_KEY);
        if (!(servers instanceof List<?> list)) {
            logger.debug("[Mcp] 声明文件未包含 {} 列表，外部工具数为 0", ROOT_KEY);
            return List.of();
        }

        List<McpServerDeclaration> declarations = new ArrayList<>();
        for (Object item : list) {
            parse(item).ifPresent(declarations::add);
        }
        logger.info("[Mcp] 已读取外部服务声明 {} 条: {}", declarations.size(),
                declarations.stream().map(McpServerDeclaration::name).toList());
        return declarations;
    }

    @SuppressWarnings("unchecked")
    private Optional<McpServerDeclaration> parse(Object item) {
        if (!(item instanceof Map<?, ?> raw)) {
            logger.warn("[Mcp] 声明条目必须是映射，已跳过: {}", item);
            return Optional.empty();
        }
        Map<String, Object> entry = (Map<String, Object>) raw;

        String name = text(entry.get("name"));
        if (name == null) {
            logger.warn("[Mcp] 声明条目缺少 name，已跳过");
            return Optional.empty();
        }

        String transport = text(entry.get("transport"));
        if (transport == null) {
            logger.warn("[Mcp] 服务 [{}] 缺少 transport，已跳过（应为 {} 或 {}）",
                    name, McpServerDeclaration.TRANSPORT_STDIO, McpServerDeclaration.TRANSPORT_SSE);
            return Optional.empty();
        }

        if (McpServerDeclaration.TRANSPORT_STDIO.equalsIgnoreCase(transport)) {
            String command = text(entry.get("command"));
            if (command == null) {
                logger.warn("[Mcp] 本地子进程服务 [{}] 缺少 command，已跳过", name);
                return Optional.empty();
            }
            return Optional.of(McpServerDeclaration.stdio(name, command,
                    stringList(entry.get("args")), stringMap(entry.get("env"))));
        }

        if (McpServerDeclaration.TRANSPORT_SSE.equalsIgnoreCase(transport)) {
            String url = text(entry.get("url"));
            if (url == null) {
                logger.warn("[Mcp] 远程服务 [{}] 缺少 url，已跳过", name);
                return Optional.empty();
            }
            return Optional.of(McpServerDeclaration.sse(name, url));
        }

        logger.warn("[Mcp] 服务 [{}] 的 transport 取值不支持: {}（应为 {} 或 {}），已跳过",
                name, transport, McpServerDeclaration.TRANSPORT_STDIO, McpServerDeclaration.TRANSPORT_SSE);
        return Optional.empty();
    }

    /** 取字符串并做环境变量占位替换，保证凭证不出现明文；空白视为未提供。 */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = ProfileLoader.substitute(String.valueOf(value));
        return text.isBlank() ? null : text;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                result.add(ProfileLoader.substitute(String.valueOf(item)));
            }
        }
        return result;
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, entryValue) -> {
            if (key != null && entryValue != null) {
                result.put(String.valueOf(key), ProfileLoader.substitute(String.valueOf(entryValue)));
            }
        });
        return result;
    }
}
