package io.oryxos.core.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ProfileRegistry 负责管理Profile的内存索引
 */
public class ProfileRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ProfileRegistry.class);
    private final Map<String, Profile> profileMap = new ConcurrentHashMap<>();
    
    /**
     * 注册Profile
     */
    public void registerProfile(String name, Profile profile) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Profile name cannot be null or empty");
        }
        
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null");
        }
        
        profileMap.put(name, profile);
        logger.debug("Registered profile: {}", name);
    }

    /**
     * 登记一个 Profile（名字取自 {@link Profile#getName()}）。
     *
     * <p>启动扫描与运行时登记共用这一个入口：登记逻辑只有一份，才谈得上"两条路径行为一致"。</p>
     *
     * @throws IllegalArgumentException 名字为空或 Profile 为 {@code null}
     */
    public void register(Profile profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Profile cannot be null");
        }
        registerProfile(profile.getName(), profile);
    }

    /**
     * 注销一个 Profile。
     *
     * <p><b>幂等</b>：名字不存在时只告警、不抛异常——注销一个已被删掉的 Agent 不该让调用方处理
     * "第二次删除失败"这种无意义的错误。</p>
     *
     * @return {@code true} 表示确实移除了一个已存在的条目
     */
    public boolean remove(String name) {
        if (name == null || name.isBlank()) {
            logger.warn("[Registry] 注销 Profile 时名字为空，已忽略");
            return false;
        }
        Profile removed = profileMap.remove(name);
        if (removed == null) {
            logger.warn("[Registry] 要注销的 Profile 不存在，已忽略: {}", name);
            return false;
        }
        logger.debug("Unregistered profile: {}", name);
        return true;
    }

    /** 是否已登记该名字（空白名一律返回 {@code false}）。 */
    public boolean exists(String name) {
        return name != null && !name.isBlank() && profileMap.containsKey(name);
    }
    
    /**
     * 获取Profile
     */
    public Profile getProfile(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Profile name cannot be null or empty");
        }
        
        return profileMap.get(name);
    }
    
    /**
     * 检查Profile是否存在
     */
    public boolean profileExists(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Profile name cannot be null or empty");
        }
        
        return profileMap.containsKey(name);
    }
    
    /**
     * 移除Profile
     */
    public void unregisterProfile(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Profile name cannot be null or empty");
        }
        
        profileMap.remove(name);
        logger.debug("Unregistered profile: {}", name);
    }
    
    /**
     * 获取所有Profile
     */
    public Map<String, Profile> getAllProfiles() {
        return new ConcurrentHashMap<>(profileMap);
    }
    
    /**
     * 清空所有Profile
     */
    public void clear() {
        profileMap.clear();
        logger.debug("Cleared all profiles");
    }
    
    /**
     * 获取Profile数量
     */
    public int getProfileCount() {
        return profileMap.size();
    }
    
    /**
     * 检查是否为空
     */
    public boolean isEmpty() {
        return profileMap.isEmpty();
    }
}