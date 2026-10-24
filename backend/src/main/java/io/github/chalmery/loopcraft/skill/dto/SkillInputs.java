package io.github.chalmery.loopcraft.skill.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 平台基本信息与文件路径独立提交；路径顺序与 multipart files 顺序相同。 */
public final class SkillInputs {
    private SkillInputs() {}

    public record Create(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*") String slug,
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 1000) String description,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 32) String> tags,
            @NotBlank
                    @Pattern(
                            regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    String requestId,
            @Size(max = 200) List<@NotBlank @Size(max = 240) String> paths) {}

    public record Metadata(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 1000) String description,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 32) String> tags,
            @NotNull Boolean enabled,
            @NotNull @PositiveOrZero Long revision) {}

    public record Save(
            @NotNull @Min(1) Integer baseVersion,
            @NotBlank
                    @Pattern(
                            regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    String requestId,
            @Size(max = 200) List<@NotBlank @Size(max = 240) String> paths) {}
}
