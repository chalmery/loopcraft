package io.github.chalmery.loopcraft.mcp.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 写入参数；鉴权头值只在创建与显式更换时出现，PATCH 中留空表示沿用原值。 */
public final class McpRequests {

    private McpRequests() {}

    public record Header(@Size(max = 128) String name, @Size(max = 8192) String value) {}

    public record ServerInput(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 32) String slug,
            @NotBlank String transport,
            @Size(max = 2048) String url,
            @Valid @Size(max = 16) List<Header> authHeaders,
            @Size(max = 512) String command,
            @Size(max = 64) List<@Size(max = 1024) String> args,
            @Size(max = 64) List<@Size(max = 1024) String> envVars,
            @Min(100) @Max(600000) Integer toolTimeoutMs,
            Boolean enabled) {

        @Override
        public String toString() {
            return "ServerInput[凭证已隐藏]";
        }
    }
}
