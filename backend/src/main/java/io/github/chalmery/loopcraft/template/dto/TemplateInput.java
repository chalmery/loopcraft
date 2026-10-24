package io.github.chalmery.loopcraft.template.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/** 完整保存配置；三个可空字段必须显式传 null，0 保留业务含义。 */
public record TemplateInput(
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String requestId,
        @PositiveOrZero Long baseRevision,
        @NotBlank @Size(max = 80) String name,
        @NotNull @Size(max = 300) String description,
        @NotNull Boolean enabled,
        @NotNull @Positive Long modelId,
        @NotNull @Size(max = 20000) String systemPrompt,
        @JsonProperty(required = true) @DecimalMin("0") @DecimalMax("100") BigDecimal temperature,
        @JsonProperty(required = true) @Positive @Max(9007199254740991L) Long contextBudgetTokens,
        @JsonProperty(required = true) @Positive Long summaryModelId,
        @NotNull @Positive Integer keepRecentMessages,
        @NotNull @PositiveOrZero @Max(9007199254740991L) Long maxIterations,
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 6) BigDecimal timeoutMinutes,
        @NotNull @Size(max = 200) List<@NotNull @Valid Binding> skillBindings) {

    /** 固定内容版本，数组顺序为添加顺序。 */
    public record Binding(@Positive long skillId, @Positive int versionNumber) {
    }
}
