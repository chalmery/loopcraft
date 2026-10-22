package io.github.chalmery.loopcraft.skill.dto;

import java.util.List;

/** 平台基本信息与文件路径独立提交；路径顺序与 multipart files 顺序相同。 */
public final class SkillInputs {
    private SkillInputs() { }
    public record Create(String slug, String name, String description, List<String> tags, String requestId,
            List<String> paths) { }
    public record Metadata(String name, String description, List<String> tags, Boolean enabled, Long revision) { }
    public record Save(Integer baseVersion, String requestId, List<String> paths) { }
}
