package io.oryxos.core.profile;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * ProfileLoader —— 启动时扫描 profiles 目录下的所有 YAML，解析成 {@link Profile} 并放入 {@link ProfileRegistry}。
 *
 * <p>职责与边界：</p>
 * <ul>
 *   <li>YAML 解析：用 SnakeYAML 读成 {@code Map}，再用 Jackson（SNAKE_CASE）转成 {@link Profile}，
 *       从而兼容 {@code mcp_servers} 这类下划线字段名。</li>
 *   <li>环境变量占位：把 {@code ${ENV_VAR}} 替换为环境变量（回退到系统属性），保证配置里不出现明文密钥。</li>
 *   <li>合法性校验：Profile 引用的 provider 名必须能在全局层（application.yaml 的 providers）找到。</li>
 *   <li>容错：坏文件只记错误日志、跳过该文件，不阻断其余 Profile 的加载与启动。</li>
 * </ul>
 */
public class ProfileLoader {

    private static final Logger logger = LoggerFactory.getLogger(ProfileLoader.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final Path profilesDir;

    /** 与 Agent 目录来源共用的校验器（规则只允许有一份）。 */
    private final ProfileValidator validator;

    private final ProfileRegistry registry = new ProfileRegistry();

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * @param profilesDir        profiles 目录
     * @param availableProviders 全局层已声明的 provider 名，用于校验 Profile 引用
     */
    public ProfileLoader(Path profilesDir, Collection<String> availableProviders) {
        this.profilesDir = Objects.requireNonNull(profilesDir, "profilesDir");
        this.validator = new ProfileValidator(availableProviders);
    }

    /** 不做 provider 引用校验的构造器（便于独立测试）。 */
    public ProfileLoader(Path profilesDir) {
        this(profilesDir, null);
    }

    /**
     * 扫描并加载目录下所有 YAML。坏文件跳过、不抛异常。
     *
     * @return 成功加载的 Profile 数量
     */
    public int loadAll() {
        registry.clear();

        if (!Files.isDirectory(profilesDir)) {
            logger.warn("Profiles 目录不存在，跳过加载: {}", profilesDir);
            return 0;
        }

        try (Stream<Path> files = Files.list(profilesDir)) {
            List<Path> yamlFiles = files
                    .filter(ProfileLoader::isYaml)
                    .sorted()
                    .toList();

            logger.info("扫描 Profiles 目录 {}，发现 {} 个 YAML 文件", profilesDir, yamlFiles.size());

            int skipped = 0;
            for (Path file : yamlFiles) {
                if (!loadFile(file)) {
                    skipped++;
                }
            }
            logger.info("Profile 加载完成：成功 {} 个，跳过 {} 个", registry.getProfileCount(), skipped);
        } catch (IOException e) {
            logger.error("扫描 profiles 目录失败: {}", profilesDir, e);
            return registry.getProfileCount();
        }

        return registry.getProfileCount();
    }

    /**
     * 加载单个 Profile 文件；解析/校验失败记日志并跳过。
     *
     * @return true 表示成功加载并注册；false 表示跳过（空文件 / 格式非法 / 校验失败 / 读取失败）
     */
    boolean loadFile(Path file) {
        String profileName = baseName(file);
        logger.info("  - 解析 Profile 文件: {}", file.getFileName());
        try {
            Map<String, Object> raw = readYaml(file);
            if (raw == null || raw.isEmpty()) {
                logger.warn("    Profile 内容为空，跳过: {}", profileName);
                return false;
            }

            Object resolved = resolvePlaceholders(raw);
            Profile profile = toProfile(resolved);
            profile.setName(profileName);

            validate(profile);

            registry.registerProfile(profileName, profile);
            logger.info("    已加载 Profile: {}（provider={}, model={}）",
                    profileName,
                    profile.getProvider() == null ? "<未配置>" : profile.getProvider().getName(),
                    profile.getProvider() == null ? "<未配置>" : profile.getProvider().getModel());
            return true;
        } catch (YAMLException e) {
            logger.error("    Profile [{}] YAML 格式非法，已跳过: {}", profileName, e.getMessage());
            return false;
        } catch (ProfileValidationException e) {
            logger.error("    Profile [{}] 校验失败，已跳过: {}", profileName, e.getMessage());
            return false;
        } catch (IOException e) {
            logger.error("    Profile [{}] 读取失败，已跳过: {}", profileName, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            logger.error("    Profile [{}] 加载异常，已跳过: {}", profileName, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            Object loaded = new Yaml().load(reader);
            if (loaded == null) {
                return null;
            }
            if (!(loaded instanceof Map)) {
                throw new YAMLException("Profile 根节点必须是映射（key: value），实际为 " + loaded.getClass().getSimpleName());
            }
            return (Map<String, Object>) loaded;
        }
    }

    private Profile toProfile(Object resolved) {
        return objectMapper.convertValue(resolved, Profile.class);
    }

    /**
     * 校验单个 Profile。
     *
     * <p>实现委托给两条来源共用的 {@link ProfileValidator}：校验规则只允许有一份，
     * 否则"目录 Agent 与 legacy Profile 同规矩"这条验收（FR-006）会在第一次改动时失效。</p>
     */
    void validate(Profile profile) {
        validator.validate(profile);
    }

    /** 递归把 {@code ${VAR}} 替换为环境变量（回退系统属性）。 */
    @SuppressWarnings("unchecked")
    static Object resolvePlaceholders(Object value) {
        if (value instanceof String s) {
            return substitute(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(entry.getKey(), resolvePlaceholders(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(resolvePlaceholders(item));
            }
            return result;
        }
        return value;
    }

    /**
     * 把 {@code ${VAR}} 替换为环境变量（回退系统属性），未设置时保持原样。
     *
     * <p>对外可见是为了让工作区里其它需要"配置不出现明文凭证"的地方复用同一份实现——
     * 占位替换的规则只应有一处，否则总有一处会漏掉。</p>
     */
    public static String substitute(String text) {
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String resolved = System.getenv(key);
            if (resolved == null) {
                resolved = System.getProperty(key);
            }
            if (resolved == null) {
                logger.warn("环境变量/系统属性 {} 未设置，占位符保持原样", key);
                resolved = matcher.group(0);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ------------------------------------------------------------------
    // 查询接口（委托给 ProfileRegistry）
    // ------------------------------------------------------------------

    public Profile get(String name) {
        return registry.getProfile(name);
    }

    public boolean has(String name) {
        return name != null && !name.isEmpty() && registry.profileExists(name);
    }

    public Map<String, Profile> all() {
        return registry.getAllProfiles();
    }

    public int count() {
        return registry.getProfileCount();
    }

    public ProfileRegistry registry() {
        return registry;
    }

    public Path profilesDir() {
        return profilesDir;
    }
}
