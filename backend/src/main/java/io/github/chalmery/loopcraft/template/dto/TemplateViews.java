package io.github.chalmery.loopcraft.template.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 模板管理响应，不返回连接凭证和 Skill 文件。 */
public final class TemplateViews {
    private TemplateViews() {
    }

    public record ModelChoice(long id, long sourceId, String name, String sourceName,
            boolean enabled, Long contextLimit, Boolean temperatureSupported,
            BigDecimal temperatureMin, BigDecimal temperatureMax) {
    }

    public record SkillBinding(long skillId, int versionNumber, String name,
            String description, boolean enabled, int latestVersion) {
    }

    public record Detail(long id, String name, String description, boolean enabled,
            long modelId, String systemPrompt, BigDecimal temperature, Long contextBudgetTokens,
            Long summaryModelId, int keepRecentMessages, long maxIterations, BigDecimal timeoutMinutes,
            long revision, OffsetDateTime updatedAt, ModelChoice model, ModelChoice summaryModel,
            List<SkillBinding> skillBindings, boolean available, List<String> unavailableReasons) {
    }

    public record Summary(long id, String name, String description, boolean enabled,
            String modelName, int skillCount, OffsetDateTime updatedAt,
            boolean available, List<String> unavailableReasons) {
    }

    public record Counts(long all, long enabled, long disabled) {
    }

    public record ListPage(List<Summary> records, long total, long page, long size, Counts counts) {
    }

    public record Page<T>(List<T> records, long total, long page, long size) {
    }
}
