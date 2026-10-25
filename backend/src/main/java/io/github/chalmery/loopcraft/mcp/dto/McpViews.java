package io.github.chalmery.loopcraft.mcp.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 服务视图只公开鉴权头名称，值永不回显。 */
public final class McpViews {

    private McpViews() {}

    public record Header(String name) {}

    public record Server(
            long id,
            String name,
            String slug,
            String transport,
            String url,
            List<Header> authHeaders,
            String command,
            List<String> args,
            List<String> envVars,
            Integer toolTimeoutMs,
            boolean enabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    public record Page<T>(List<T> records, long total, long page, long size) {}

    public record ToolSummary(String name, String description) {}

    /** 连接探测结果；失败也是正常业务结果，ok=false 时仅带原因。 */
    public record TestResult(
            boolean ok,
            String reason,
            String protocolVersion,
            String serverName,
            String serverVersion,
            String transport,
            List<ToolSummary> tools) {

        public static TestResult ok(
                String protocolVersion,
                String serverName,
                String serverVersion,
                String transport,
                List<ToolSummary> tools) {
            return new TestResult(true, null, protocolVersion, serverName, serverVersion, transport, tools);
        }

        public static TestResult failed(String reason) {
            return new TestResult(false, reason, null, null, null, null, List.of());
        }
    }

    public record Options(boolean stdioEnabled) {}
}
