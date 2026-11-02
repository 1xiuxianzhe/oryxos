package io.oryxos.core.profile;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Profile 配置类
 * 包含Agent的完整配置信息，包括Provider、工具、记忆等设置
 */
public class Profile {
    
    private static final Logger logger = LoggerFactory.getLogger(Profile.class);

    /** 循环最大轮数默认值。 */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    /** 会话历史保留的完整往返数默认值。 */
    public static final int DEFAULT_MAX_HISTORY_TURNS = 20;

    /** 单次处理整体耗时上限默认值（秒）。 */
    public static final int DEFAULT_PROCESSING_TIMEOUT_SECONDS = 60;

    /** 单次工具执行耗时上限默认值（秒）：小于整体上限，留出失败收尾的余量。 */
    public static final int DEFAULT_TOOL_TIMEOUT_SECONDS = 30;

    private String name;
    private String description;
    private String identity;
    
    private ProviderConfig provider;
    private List<ToolConfig> tools;
    private List<SkillConfig> skills;
    private List<McpServerConfig> mcpServers;
    private List<ChannelConfig> channels;
    private List<NotifyChannelConfig> notifyChannels;
    private List<ScheduleConfig> schedules;
    private BootstrapConfig bootstrap;
    private Map<String, Object> settings;

    /**
     * 这个 Agent 的目录（仅"目录来源"的 Agent 有值，legacy {@code profiles/*.yaml} 为 {@code null}）。
     *
     * <p>由 {@code AgentLoader} 在派生时设置，是正文、资源、脚本白名单、独立记忆的定位基准。
     * 刻意 {@link JsonIgnore}：它描述的是"文件在哪"，不是可配置项——允许 YAML 手写等于允许
     * 把任意目录声明成某个 Agent 的家。</p>
     */
    private Path agentDir;
    
    // Getters and Setters
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getIdentity() {
        return identity;
    }
    
    public void setIdentity(String identity) {
        this.identity = identity;
    }
    
    public ProviderConfig getProvider() {
        return provider;
    }
    
    public void setProvider(ProviderConfig provider) {
        this.provider = provider;
    }
    
    public List<ToolConfig> getTools() {
        return tools;
    }
    
    public void setTools(List<ToolConfig> tools) {
        this.tools = tools;
    }
    
    public List<SkillConfig> getSkills() {
        return skills;
    }
    
    public void setSkills(List<SkillConfig> skills) {
        this.skills = skills;
    }
    
    public List<McpServerConfig> getMcpServers() {
        return mcpServers;
    }
    
    public void setMcpServers(List<McpServerConfig> mcpServers) {
        this.mcpServers = mcpServers;
    }
    
    public List<ChannelConfig> getChannels() {
        return channels;
    }
    
    public void setChannels(List<ChannelConfig> channels) {
        this.channels = channels;
    }
    
    public List<NotifyChannelConfig> getNotifyChannels() {
        return notifyChannels;
    }
    
    public void setNotifyChannels(List<NotifyChannelConfig> notifyChannels) {
        this.notifyChannels = notifyChannels;
    }
    
    public List<ScheduleConfig> getSchedules() {
        return schedules;
    }
    
    public void setSchedules(List<ScheduleConfig> schedules) {
        this.schedules = schedules;
    }
    
    public BootstrapConfig getBootstrap() {
        return bootstrap;
    }
    
    public void setBootstrap(BootstrapConfig bootstrap) {
        this.bootstrap = bootstrap;
    }
    
    public Map<String, Object> getSettings() {
        return settings;
    }
    
    public void setSettings(Map<String, Object> settings) {
        this.settings = settings;
    }

    /** Agent 目录；legacy Profile 为 {@code null}。 */
    @JsonIgnore
    public Path getAgentDir() {
        return agentDir;
    }

    @JsonIgnore
    public void setAgentDir(Path agentDir) {
        this.agentDir = agentDir;
    }

    /** 是否来自 Agent 目录（决定正文来源、工具白名单与记忆落点）。 */
    @JsonIgnore
    public boolean isFromAgentDirectory() {
        return agentDir != null;
    }

    /**
     * bootstrap 显式引用的文件列表（相对 .oryxos/ 的路径）；未配置时返回空列表。
     * 显式引用缺失要报错，未配置时按默认三件套尝试加载、缺失只告警。
     */
    public List<String> bootstrapFiles() {
        if (bootstrap == null || bootstrap.getFiles() == null) {
            return List.of();
        }
        return List.copyOf(bootstrap.getFiles());
    }

    /** 循环最大轮数：settings.max_iterations，默认 10。 */
    public int maxIterations() {
        return settingAsInt("max_iterations", DEFAULT_MAX_ITERATIONS);
    }

    /** 会话历史保留的完整往返数：settings.max_history_turns，默认 20。 */
    public int maxHistoryTurns() {
        return settingAsInt("max_history_turns", DEFAULT_MAX_HISTORY_TURNS);
    }

    /** 单次处理整体耗时上限（秒）：settings.processing_timeout_seconds，默认 60。 */
    public int processingTimeoutSeconds() {
        return settingAsInt("processing_timeout_seconds", DEFAULT_PROCESSING_TIMEOUT_SECONDS);
    }

    /**
     * 单次工具执行耗时上限（秒）：settings.tool_timeout_seconds，默认 30。
     *
     * <p>必须小于 {@link #processingTimeoutSeconds()}：工具卡死时留出余量，
     * 让循环有机会把失败结果回填给模型收尾，而不是整个请求一起超时。</p>
     */
    public int toolTimeoutSeconds() {
        return settingAsInt("tool_timeout_seconds", DEFAULT_TOOL_TIMEOUT_SECONDS);
    }

    /** 读取 settings 中的正整数；缺失或非法一律回退默认值并记 WARN（不让一个坏配置拖垮 Agent）。 */
    private int settingAsInt(String key, int defaultValue) {
        if (settings == null) {
            return defaultValue;
        }
        Object value = settings.get(key);
        if (value == null) {
            return defaultValue;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException e) {
                logger.warn("Profile [{}] 的 settings.{} 非法（{}），回退默认值 {}",
                        name, key, value, defaultValue);
                return defaultValue;
            }
        }
        if (parsed < 1) {
            logger.warn("Profile [{}] 的 settings.{} 小于 1（{}），回退默认值 {}",
                    name, key, parsed, defaultValue);
            return defaultValue;
        }
        return parsed;
    }
    
    /**
     * Provider配置子类
     */
    public static class ProviderConfig {
        private String name;
        private String model;
        private Double temperature;
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getModel() {
            return model;
        }
        
        public void setModel(String model) {
            this.model = model;
        }
        
        public Double getTemperature() {
            return temperature;
        }
        
        public void setTemperature(Double temperature) {
            this.temperature = temperature;
        }
    }
    
    /**
     * Tool配置子类
     */
    public static class ToolConfig {
        private String name;
        private String description;
        private List<ParameterConfig> parameters;

        /**
         * 兼容工具的简写声明：{@code tools: [read_file, shell]}。
         *
         * <p>等价于 {@code tools: [{name: read_file}, {name: shell}]}，两种写法都只取名字参与授权过滤。
         * 用委托式创建器（DELEGATING）而非属性式：输入是标量（字符串）时走这里，
         * 输入是映射时仍走默认构造器 + setter，既有写法不受影响。</p>
         */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ToolConfig ofName(String name) {
            ToolConfig config = new ToolConfig();
            config.setName(name);
            return config;
        }

        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public List<ParameterConfig> getParameters() {
            return parameters;
        }
        
        public void setParameters(List<ParameterConfig> parameters) {
            this.parameters = parameters;
        }
        
        /**
         * 参数配置子类
         */
        public static class ParameterConfig {
            private String name;
            private String description;
            private String type;
            private boolean required;
            private Object defaultValue;
            
            public String getName() {
                return name;
            }
            
            public void setName(String name) {
                this.name = name;
            }
            
            public String getDescription() {
                return description;
            }
            
            public void setDescription(String description) {
                this.description = description;
            }
            
            public String getType() {
                return type;
            }
            
            public void setType(String type) {
                this.type = type;
            }
            
            public boolean isRequired() {
                return required;
            }
            
            public void setRequired(boolean required) {
                this.required = required;
            }
            
            public Object getDefaultValue() {
                return defaultValue;
            }
            
            public void setDefaultValue(Object defaultValue) {
                this.defaultValue = defaultValue;
            }
        }
    }
    
    /**
     * Skill配置子类
     */
    public static class SkillConfig {
        private String name;
        private String description;
        private String prompt;
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getDescription() {
            return description;
        }
        
        public void setDescription(String description) {
            this.description = description;
        }
        
        public String getPrompt() {
            return prompt;
        }
        
        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
    }
    
    /**
     * MCP Server配置子类
     */
    public static class McpServerConfig {
        private String name;
        private String url;
        private String apiKey;
        
        public String getName() {
            return name;
        }
        
        public void setName(String name) {
            this.name = name;
        }
        
        public String getUrl() {
            return url;
        }
        
        public void setUrl(String url) {
            this.url = url;
        }
        
        public String getApiKey() {
            return apiKey;
        }
        
        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
    
    /**
     * Channel配置子类
     */
    public static class ChannelConfig {
        private String type;
        private Map<String, Object> config;
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public Map<String, Object> getConfig() {
            return config;
        }
        
        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }
    }
    
    /**
     * 通知Channel配置子类
     */
    public static class NotifyChannelConfig {
        private String type;
        private Map<String, Object> config;
        
        public String getType() {
            return type;
        }
        
        public void setType(String type) {
            this.type = type;
        }
        
        public Map<String, Object> getConfig() {
            return config;
        }
        
        public void setConfig(Map<String, Object> config) {
            this.config = config;
        }
    }
    
    /**
     * Schedule 配置子类：一条定时声明。
     *
     * <p>字段语义见 {@code specs/008-scheduler/contracts/scheduler.md}：</p>
     * <ul>
     *   <li>{@code id}——Profile 内唯一标识，防重叠锁与日志定位都靠它（必填）；</li>
     *   <li>{@code cron}——Spring 6 段表达式（秒 分 时 日 月 周）必填；</li>
     *   <li>{@code timezone}——IANA 时区名；缺省用系统时区，并在启动日志写明；</li>
     *   <li>{@code message}——到点交给 Agent 的话；</li>
     *   <li>{@code task}——<b>兼容旧字段</b>，{@code message} 为空时作为消息内容。</li>
     * </ul>
     */
    public static class ScheduleConfig {
        private String id;
        private String cron;
        private String timezone;
        private String message;
        private String task;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getTask() {
            return task;
        }

        public void setTask(String task) {
            this.task = task;
        }
    }
    
    /**
     * Bootstrap配置子类
     */
    public static class BootstrapConfig {
        private String prompt;
        private List<String> skills;

        /** 显式引用的 Bootstrap 文件列表（相对 .oryxos/ 的路径）；缺失要报错。 */
        private List<String> files;
        
        public String getPrompt() {
            return prompt;
        }
        
        public void setPrompt(String prompt) {
            this.prompt = prompt;
        }
        
        public List<String> getSkills() {
            return skills;
        }
        
        public void setSkills(List<String> skills) {
            this.skills = skills;
        }

        public List<String> getFiles() {
            return files;
        }

        public void setFiles(List<String> files) {
            this.files = files;
        }
    }
    
    @Override
    public String toString() {
        return "Profile{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", identity='" + identity + '\'' +
                ", provider=" + provider +
                ", tools=" + tools +
                ", skills=" + skills +
                ", mcpServers=" + mcpServers +
                ", channels=" + channels +
                ", notifyChannels=" + notifyChannels +
                ", schedules=" + schedules +
                ", bootstrap=" + bootstrap +
                ", settings=" + settings +
                '}';
    }
}