package io.github.chalmery.loopcraft.llm.service;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 创建和局部更新共用的输入边界。 */
final class LlmInputs {
    private LlmInputs() { }

    static String text(Object value, String field, int max) {
        if (!(value instanceof String raw) || raw.strip().isEmpty() || raw.strip().length() > max) {
            throw invalid(field + "不能为空且长度不能超过 " + max);
        }
        return raw.strip();
    }

    static String protocol(Object value) {
        String protocol = text(value, "协议", 40);
        if (!Set.of("OPENAI_COMPATIBLE", "ANTHROPIC_COMPATIBLE").contains(protocol)) throw invalid("未知协议");
        return protocol;
    }

    static String baseUrl(Object value) {
        String url = text(value, "API 根地址", 2048);
        try {
            URI uri = URI.create(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || (uri.getPort() == 0 || uri.getPort() > 65535) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) throw new IllegalArgumentException();
            String path = uri.getPath().replaceAll("/+$", "");
            if (path.endsWith("/models") || path.endsWith("/messages") || path.endsWith("/chat/completions")) {
                throw new IllegalArgumentException();
            }
            return url.replaceAll("/+$", "");
        } catch (IllegalArgumentException exception) {
            throw invalid("请填写 HTTP(S) API 根地址，包含版本前缀，不含凭证、查询参数或具体接口路径");
        }
    }

    static boolean bool(Object value) {
        if (!(value instanceof Boolean flag)) throw invalid("状态字段必须为布尔值");
        return flag;
    }

    static String apiKey(Object value) {
        if (value == null) return "";
        if (!(value instanceof String raw) || raw.length() > 8192) throw invalid("API Key 格式或长度不合法");
        String key = raw.strip();
        if (key.chars().anyMatch(c -> c < 32 || c > 126)) throw invalid("API Key 含非法字符");
        return key;
    }

    static void fields(Map<String, Object> patch, Set<String> allowed) {
        if (!allowed.containsAll(patch.keySet())) throw invalid("包含未知字段");
    }

    static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
