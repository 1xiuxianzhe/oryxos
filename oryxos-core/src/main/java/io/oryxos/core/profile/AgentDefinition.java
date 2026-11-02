package io.oryxos.core.profile;

import java.nio.file.Path;
import java.util.Map;

/**
 * 一个 Agent 目录的解析结果：**配置**（frontmatter）+ **指令**（正文）+ **资源位置**。
 *
 * <p>刻意把"解析"与"派生 Profile"分开：解析只回答"这个目录里有什么"，
 * 派生回答"它对应底座认识的哪个 Profile"。两者合在一起时，任何一次"读取失败"都会
 * 被误当成"配置非法"，报错信息也就跟着失真。</p>
 *
 * @param name         Agent 名（= 目录名）
 * @param dir          Agent 目录（绝对、已归一化）
 * @param frontmatter  frontmatter 解析结果（已做 ${ENV} 替换）
 * @param body         正文（已剥离 frontmatter，保留原始换行）
 * @param referenceFile {@code REFERENCE.md} 路径（可能不存在）
 * @param skillsDir    {@code skills/} 目录（可能不存在）
 * @param scriptsDir   {@code scripts/} 目录（可能不存在）
 */
public record AgentDefinition(String name,
                              Path dir,
                              Map<String, Object> frontmatter,
                              String body,
                              Path referenceFile,
                              Path skillsDir,
                              Path scriptsDir) {

    /** 正文是否为空（只有配置、没有指令也算合法，只是要提示）。 */
    public boolean hasEmptyBody() {
        return body == null || body.isBlank();
    }
}
