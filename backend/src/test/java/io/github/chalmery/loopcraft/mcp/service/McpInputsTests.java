package io.github.chalmery.loopcraft.mcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.chalmery.loopcraft.mcp.dto.McpRequests;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** 输入边界的字段规则：slug、传输枚举、URL、stdio 令牌与超时范围。 */
class McpInputsTests {

    @Test
    void slugRequiresLowercaseIdentifierShape() {
        assertThat(McpInputs.slug("context7")).isEqualTo("context7");
        assertThat(McpInputs.slug("a-1")).isEqualTo("a-1");
        for (String bad : List.of("Context7", "7context", "con text", "c", "-ctx", "x".repeat(33))) {
            assertThatThrownBy(() -> McpInputs.slug(bad))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void transportAcceptsOnlyThreeKinds() {
        assertThat(McpInputs.transport("HTTP_STREAMABLE")).isEqualTo("HTTP_STREAMABLE");
        assertThat(McpInputs.transport("HTTP_SSE")).isEqualTo("HTTP_SSE");
        assertThat(McpInputs.transport("STDIO")).isEqualTo("STDIO");
        assertThatThrownBy(() -> McpInputs.transport("HTTP"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void urlRequiresPlainHttpForm() {
        assertThat(McpInputs.url("https://mcp.example.com/mcp"))
                .isEqualTo("https://mcp.example.com/mcp");
        assertThat(McpInputs.url("http://127.0.0.1:9000/sse")).isEqualTo("http://127.0.0.1:9000/sse");
        for (String bad :
                List.of(
                        "ftp://example.com",
                        "https://user:pass@example.com/mcp",
                        "https://example.com/mcp?token=1",
                        "https://example.com/mcp#frag",
                        "not-a-url",
                        "https://:9000/mcp")) {
            assertThatThrownBy(() -> McpInputs.url(bad))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void envVarsRequireKeyEqualsValueShape() {
        assertThat(McpInputs.envVars(List.of("FS_ROOT=/data"))).containsExactly("FS_ROOT=/data");
        assertThat(McpInputs.envVars(null)).isEmpty();
        for (List<String> bad :
                List.of(List.of("NO_EQ"), List.of("1BAD=/x"), List.of("=v"), List.of(" A =b"))) {
            assertThatThrownBy(() -> McpInputs.envVars(bad))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void argsRejectEmptyAndOversizedTokens() {
        assertThat(McpInputs.args(List.of("npx", "--flag"))).containsExactly("npx", "--flag");
        assertThatThrownBy(() -> McpInputs.args(List.of(" ")))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> McpInputs.args(List.of("x".repeat(1025))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> McpInputs.args("npx"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void toolTimeoutAllowsNullOrBoundedMillis() {
        assertThat(McpInputs.toolTimeoutMs(null)).isNull();
        assertThat(McpInputs.toolTimeoutMs(60000)).isEqualTo(60000);
        assertThatThrownBy(() -> McpInputs.toolTimeoutMs(99))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> McpInputs.toolTimeoutMs(600001))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> McpInputs.toolTimeoutMs("60000"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void headerNamesStayPrintableWithoutColon() {
        assertThat(McpInputs.headerName("Authorization")).isEqualTo("Authorization");
        assertThat(McpInputs.headerName("X-Api-Key")).isEqualTo("X-Api-Key");
        assertThatThrownBy(() -> McpInputs.headerName("Bad:Header"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> McpInputs.headerName("空 格"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void requestToStringNeverExposesCredentials() {
        McpRequests.ServerInput input =
                new McpRequests.ServerInput(
                        "名称", "slug", "HTTP_STREAMABLE", "https://mcp.example.com/mcp",
                        List.of(new McpRequests.Header("Authorization", "secret-token")),
                        null, null, null, null, true);
        assertThat(input.toString()).doesNotContain("secret-token").contains("凭证已隐藏");
    }
}
