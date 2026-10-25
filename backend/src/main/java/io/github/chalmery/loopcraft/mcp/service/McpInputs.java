package io.github.chalmery.loopcraft.mcp.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 创建和局部更新共用的输入边界；按传输类型校验互斥必填字段。 */
final class McpInputs {

    private static final Set<String> TRANSPORTS = Set.of("HTTP_STREAMABLE", "HTTP_SSE", "STDIO");

    private McpInputs() {}

    static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    static String text(Object value, String field, int max) {
        if (!(value instanceof String raw) || raw.strip().isEmpty() || raw.strip().length() > max) {
            throw invalid(field + "不能为空且长度不能超过 " + max);
        }
        return raw.strip();
    }

    /** 小写字母开头的 2-32 位 slug，与工具名前缀字符集一致。 */
    static String slug(Object value) {
        String slug = text(value, "标识 slug", 32);
        if (!slug.matches("^[a-z][a-z0-9-]{1,31}$")) {
            throw invalid("slug 需为 2-32 位小写字母、数字、连字符，且以字母开头");
        }
        return slug;
    }

    static String transport(Object value) {
        String transport = text(value, "传输类型", 20);
        if (!TRANSPORTS.contains(transport)) {
            throw invalid("未知传输类型");
        }
        return transport;
    }

    /** 远程服务地址：HTTP(S)、带主机名，不含凭证、查询串或片段。 */
    static String url(Object value) {
        String url = text(value, "服务地址", 2048);
        try {
            URI uri = URI.create(url);
            boolean httpScheme =
                    "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
            boolean invalidPort = uri.getPort() == 0 || uri.getPort() > 65535;
            boolean containsEmbeddedData =
                    uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null;
            if (!httpScheme || invalidPort || uri.getHost() == null || containsEmbeddedData) {
                throw new IllegalArgumentException();
            }
            return url;
        } catch (IllegalArgumentException exception) {
            throw invalid("服务地址必须为 HTTP(S) URL，不含凭证、查询参数或片段");
        }
    }

    static String command(Object value) {
        String command = text(value, "stdio 命令", 512);
        if (command.chars().anyMatch(c -> c < 32 || c > 126)) {
            throw invalid("stdio 命令仅允许可打印 ASCII 字符");
        }
        return command;
    }

    static List<String> args(Object value) {
        return tokens(value, "参数");
    }

    /** 环境变量每行 KEY=VALUE，键为常规环境变量名。 */
    static List<String> envVars(Object value) {
        List<String> vars = tokens(value, "环境变量");
        for (String item : vars) {
            int eq = item.indexOf('=');
            if (eq <= 0 || !item.substring(0, eq).matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                throw invalid("环境变量需为 KEY=VALUE 形式，且键为合法环境变量名");
            }
        }
        return vars;
    }

    private static List<String> tokens(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> raw) || raw.size() > 64) {
            throw invalid(field + "必须为最多 64 项的字符串数组");
        }
        List<String> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof String token)) {
                throw invalid(field + "数组内必须为字符串");
            }
            String trimmed = token.strip();
            if (trimmed.isEmpty() || trimmed.length() > 1024) {
                throw invalid(field + "不能包含空项，且单项不超过 1024 字符");
            }
            result.add(trimmed);
        }
        return List.copyOf(result);
    }

    /** 工具超时毫秒：空值沿用全局 toolTimeout，设置时限制在 100ms 到 10 分钟。 */
    static Integer toolTimeoutMs(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number)) {
            throw invalid("工具超时必须为毫秒数值");
        }
        int millis = number.intValue();
        if (millis < 100 || millis > 600000) {
            throw invalid("工具超时需在 100 到 600000 毫秒之间");
        }
        return millis;
    }

    static boolean bool(Object value) {
        if (!(value instanceof Boolean flag)) {
            throw invalid("状态字段必须为布尔值");
        }
        return flag;
    }

    static void fields(Map<String, Object> patch, Set<String> allowed) {
        if (!allowed.containsAll(patch.keySet())) {
            throw invalid("包含未知字段");
        }
    }

    static String headerName(Object value) {
        String name = text(value, "鉴权头名称", 128);
        if (name.chars().anyMatch(c -> c < 33 || c > 126) || name.contains(":")) {
            throw invalid("鉴权头名称仅允许可打印 ASCII 字符且不含冒号");
        }
        return name;
    }

    static String headerValue(Object value) {
        if (!(value instanceof String raw) || raw.length() > 8192) {
            throw invalid("鉴权头值格式或长度不合法");
        }
        return raw;
    }
}
