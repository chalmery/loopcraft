package io.github.chalmery.loopcraft.skill.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 响应不包含 Bucket、对象 Key 或存储凭证。 */
public final class SkillViews {
    private SkillViews() { }
    public record Summary(long id, String slug, String name, String description, List<String> tags, String origin,
            boolean enabled, int latestVersion, long revision, OffsetDateTime updatedAt,
            int fileCount, OffsetDateTime contentUpdatedAt) { }
    /** 来源数量基于整个租户，独立于当前关键词与分页。 */
    public record Counts(long custom, long system) { }
    public record ListPage(List<Summary> records, long total, long page, long size, Counts counts) { }
    public record Page<T>(List<T> records, long total, long page, long size) { }
    public record Version(int number, int fileCount, long totalBytes, long createdBy, OffsetDateTime createdAt) { }
    public record File(String path, long size, String text, String base64) { }
    public record Content(int number, List<File> files) { }
    /** 新建导入的建议值，由用户确认后随创建表单提交，不写回包内文件。 */
    public record Preview(int number, List<File> files, String slug, String name, String description) { }
}
