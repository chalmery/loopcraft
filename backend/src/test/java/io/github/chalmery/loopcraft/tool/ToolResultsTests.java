package io.github.chalmery.loopcraft.tool;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** envelope 契约：成功、截断与失败路径都必须返回模型可直接读取的合法 JSON（提案 9.1/11）。 */
class ToolResultsTests {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void dataWithinLimitKeepsPlainEnvelope() {
        String result = ToolResults.data(json, java.util.List.of("条目一", "条目二"), 8000);

        JsonNode node = json.readTree(result);
        assertThat(node.path("ok").asBoolean()).isTrue();
        assertThat(node.has("truncated")).isFalse();
        assertThat(node.path("data").get(0).asString()).isEqualTo("条目一");
        assertThat(result.length()).isLessThanOrEqualTo(8000);
    }

    @Test
    void oversizedDataDegradesToTruncatedEnvelopeWithinLimit() {
        // maxChars 按 char 计数，取值需确保序列化后的完整 envelope 超过上限。
        String huge = "长".repeat(8100);

        String result = ToolResults.data(json, huge, 8000);

        JsonNode node = json.readTree(result);
        assertThat(node.path("ok").asBoolean()).isTrue();
        assertThat(node.path("truncated").asBoolean()).isTrue();
        assertThat(node.path("totalChars").asInt()).isGreaterThan(8000);
        assertThat(result.length()).isLessThanOrEqualTo(8000);
        // data() 截断保留的是序列化后 JSON 文本的前缀，仍是完整字符片段。
        assertThat(node.path("data").asString()).startsWith("{\"ok\":true,\"data\":\"长").endsWith("长");
    }

    @Test
    void oversizedTextTruncatesBeforeWrappingEnvelope() {
        String text = "文档".repeat(2000);

        String result = ToolResults.text(json, text, 1000);

        JsonNode node = json.readTree(result);
        assertThat(node.path("ok").asBoolean()).isTrue();
        assertThat(node.path("truncated").asBoolean()).isTrue();
        assertThat(node.path("totalChars").asInt()).isEqualTo(text.length());
        assertThat(result.length()).isLessThanOrEqualTo(1000);
        assertThat(node.path("data").asString()).isEqualTo("文档".repeat(400));
    }

    @Test
    void textWithinLimitKeepsFullContent() {
        String text = "完整文档内容";

        String result = ToolResults.text(json, text, 1000);

        JsonNode node = json.readTree(result);
        assertThat(node.path("ok").asBoolean()).isTrue();
        assertThat(node.has("truncated")).isFalse();
        assertThat(node.path("data").asString()).isEqualTo(text);
    }

    @Test
    void errorEnvelopeCarriesStableCodeWithoutOkFlag() {
        String result = ToolResults.error(json, "SKILL_NOT_BOUND", "slug 未绑定");

        JsonNode node = json.readTree(result);
        assertThat(node.path("ok").asBoolean()).isFalse();
        assertThat(node.path("error").path("code").asString()).isEqualTo("SKILL_NOT_BOUND");
        assertThat(node.path("error").path("message").asString()).isEqualTo("slug 未绑定");
    }
}
