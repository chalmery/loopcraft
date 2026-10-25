package io.github.chalmery.loopcraft.tool;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工具结果统一 envelope：成功、截断与失败都是合法 JSON，模型可在下一轮直接读取。 */
final class ToolResults {

    /** 截断时为 envelope 结构预留的字符数，保证整体不超过结果上限。 */
    private static final int ENVELOPE_OVERHEAD = 200;

    private ToolResults() {}

    /** 对象类结果：常规路径直接序列化，超限时降级为带 truncated 标识的截断文本。 */
    static String data(ObjectMapper json, Object data, int maxChars) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("data", data);
        String full = json.writeValueAsString(envelope);
        if (full.length() <= maxChars) {
            return full;
        }
        Map<String, Object> truncated = new LinkedHashMap<>();
        truncated.put("ok", true);
        truncated.put("truncated", true);
        truncated.put("totalChars", full.length());
        truncated.put("data", full.substring(0, Math.max(0, maxChars - ENVELOPE_OVERHEAD)));
        return json.writeValueAsString(truncated);
    }

    /** 文本类结果：先按原文截断再封 envelope，避免模型读到 JSON 转义片段。 */
    static String text(ObjectMapper json, String text, int maxChars) {
        if (text.length() <= maxChars - ENVELOPE_OVERHEAD) {
            return data(json, text, maxChars);
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", true);
        envelope.put("truncated", true);
        envelope.put("totalChars", text.length());
        envelope.put("data", text.substring(0, Math.max(0, maxChars - ENVELOPE_OVERHEAD)));
        return json.writeValueAsString(envelope);
    }

    /** 可预期失败：不终止运行，交给模型在剩余预算内修正或解释。 */
    static String error(ObjectMapper json, String code, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ok", false);
        envelope.put("error", error);
        return json.writeValueAsString(envelope);
    }
}
