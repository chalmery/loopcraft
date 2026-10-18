package io.github.chalmery.loopcraft.llm.dto;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/** 写入参数；PATCH 另外按字段是否出现区分保留与非法空值。 */
public final class LlmRequests {
    private LlmRequests() { }

    public record CreateSource(@NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 80) String supplierName, @NotBlank String protocol,
            @NotBlank @Size(max = 2048) String baseUrl, @Size(max = 8192) String apiKey, Boolean enabled) {
        @Override public String toString() { return "CreateSource[凭证已隐藏]"; }
    }
    public record ModelInput(@NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 160) String modelId) { }
    public record ImportModels(@NotEmpty @Size(max = 100) List<@NotNull @Valid ModelInput> models) { }
    public record Discover(@Size(max = 160) String cursor, @Min(1) @Max(100) Integer limit) { }
}
