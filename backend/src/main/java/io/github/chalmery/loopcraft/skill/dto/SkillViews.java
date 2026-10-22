package io.github.chalmery.loopcraft.skill.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 响应不包含 Bucket、对象 Key 或存储凭证。 */
public final class SkillViews {
    private SkillViews() { }
    public record Summary(long id, String slug, String name, String description, List<String> tags, String origin,
            boolean enabled, int latestVersion, long revision, OffsetDateTime updatedAt) { }
    public record Page<T>(List<T> records, long total, long page, long size) { }
    public record Version(int number, int fileCount, long totalBytes, long createdBy, OffsetDateTime createdAt) { }
    public record File(String path, long size, String text, String base64) { }
    public record Content(int number, List<File> files) { }
}
