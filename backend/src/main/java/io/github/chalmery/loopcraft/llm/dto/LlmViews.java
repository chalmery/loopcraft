package io.github.chalmery.loopcraft.llm.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 来源视图始终只公开凭证是否已配置。 */
public final class LlmViews {
    private LlmViews() { }

    public record Source(long id, String name, String supplierName, String protocol, String baseUrl,
            boolean hasApiKey, boolean enabled, long modelCount, OffsetDateTime updatedAt) { }
    public record Model(long id, long sourceId, String name, String modelId, String origin,
            boolean enabled, OffsetDateTime updatedAt) { }
    public record Page<T>(List<T> records, long total, long page, long size) { }
    public record Candidate(String name, String modelId) { }
    public record Discovery(List<Candidate> records, String nextCursor, boolean supported) { }
    public record Imported(List<Model> created, List<String> skippedModelIds) { }
}
