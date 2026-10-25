package io.github.chalmery.loopcraft.mcp.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.github.chalmery.loopcraft.identity.model.UserAccount;
import io.github.chalmery.loopcraft.identity.service.IdentityService;
import io.github.chalmery.loopcraft.llm.service.LlmCredentialCipher;
import io.github.chalmery.loopcraft.mcp.config.McpProperties;
import io.github.chalmery.loopcraft.mcp.dto.McpRequests;
import io.github.chalmery.loopcraft.mcp.dto.McpViews;
import io.github.chalmery.loopcraft.mcp.mapper.McpServerMapper;
import io.github.chalmery.loopcraft.mcp.model.McpServer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MCP 服务配置的租户权限、校验与持久化；成员只读，负责人和平台管理员可管理。 */
@Service
public class McpServerService {

    private static final Set<String> PATCH_FIELDS = Set.of(
            "name", "slug", "transport", "url", "authHeaders", "command", "args", "envVars",
            "toolTimeoutMs", "enabled");

    private final IdentityService identity;

    private final ObjectProvider<McpServerMapper> mappers;

    private final LlmCredentialCipher cipher;

    private final McpConnectionProbe probe;

    private final McpProperties properties;

    private final ObjectMapper json;

    public McpServerService(
            IdentityService identity,
            ObjectProvider<McpServerMapper> mappers,
            LlmCredentialCipher cipher,
            McpConnectionProbe probe,
            McpProperties properties,
            ObjectMapper json) {
        this.identity = identity;
        this.mappers = mappers;
        this.cipher = cipher;
        this.probe = probe;
        this.properties = properties;
        this.json = json;
    }

    /** 分页查询当前租户服务清单。 */
    public McpViews.Page<McpViews.Server> servers(
            UserAccount actor, long tenantId, int page, int size, String q) {
        identity.tenant(actor, tenantId, false);
        String keyword = q == null ? "" : q.strip();
        var result =
                mapper()
                        .selectPage(
                                new Page<>(page, size),
                                Wrappers.<McpServer>lambdaQuery()
                                        .eq(McpServer::getTenantId, tenantId)
                                        .like(!keyword.isEmpty(), McpServer::getName, keyword)
                                        .orderByDesc(McpServer::getUpdatedAt, McpServer::getId));
        return new McpViews.Page<>(
                result.getRecords().stream().map(this::view).toList(),
                result.getTotal(),
                page,
                size);
    }

    /** 连接参数摘要；鉴权头仅返回名称。 */
    public McpViews.Server server(UserAccount actor, long tenantId, long serverId) {
        return view(owned(actor, tenantId, serverId, false));
    }

    /** stdio 开关等前端渲染所需的选项。 */
    public McpViews.Options options(UserAccount actor, long tenantId) {
        identity.tenant(actor, tenantId, false);
        return new McpViews.Options(properties.stdioEnabled());
    }

    public McpViews.Server create(UserAccount actor, long tenantId, McpRequests.ServerInput input) {
        identity.tenant(actor, tenantId, true);
        McpServer server = new McpServer();
        server.setTenantId(tenantId);
        apply(server, input);
        requireSlugAvailable(tenantId, server.getSlug(), null);
        server.setEnabled(input.enabled() == null || input.enabled());
        server.setCreatedAt(OffsetDateTime.now());
        server.setUpdatedAt(server.getCreatedAt());
        mapper().insert(server);
        return view(server);
    }

    /** 局部更新；字段出现即覆盖，鉴权头清单整体替换、同名留空沿用原值。 */
    public McpViews.Server update(
            UserAccount actor, long tenantId, long serverId, Map<String, Object> patch) {
        McpServer server = owned(actor, tenantId, serverId, true);
        McpInputs.fields(patch, PATCH_FIELDS);
        Map<String, String> existing = storedHeaders(server.getAuthHeaders());
        String slug = patch.containsKey("slug") ? McpInputs.slug(patch.get("slug")) : server.getSlug();
        String transport =
                patch.containsKey("transport") ? McpInputs.transport(patch.get("transport")) : server.getTransport();
        if (patch.containsKey("transport") && isStdio(transport) && !properties.stdioEnabled()) {
            throw McpInputs.invalid("stdio 总开关未启用（loopcraft.mcp.stdio.enabled=false），不能保存 stdio 服务");
        }
        if (!slug.equals(server.getSlug())) {
            requireSlugAvailable(tenantId, slug, server.getId());
        }
        server.setSlug(slug);
        server.setTransport(transport);
        if (patch.containsKey("name")) {
            server.setName(McpInputs.text(patch.get("name"), "名称", 80));
        }
        if (patch.containsKey("url")) {
            server.setUrl(isStdio(transport) ? null : McpInputs.url(patch.get("url")));
        }
        if (patch.containsKey("command")) {
            server.setCommand(isStdio(transport) ? McpInputs.command(patch.get("command")) : null);
        }
        if (patch.containsKey("args")) {
            server.setArgs(isStdio(transport) ? serialize(McpInputs.args(patch.get("args"))) : null);
        }
        if (patch.containsKey("envVars")) {
            server.setEnvVars(isStdio(transport) ? serialize(McpInputs.envVars(patch.get("envVars"))) : null);
        }
        if (patch.containsKey("toolTimeoutMs")) {
            server.setToolTimeoutMs(McpInputs.toolTimeoutMs(patch.get("toolTimeoutMs")));
        }
        if (patch.containsKey("enabled")) {
            server.setEnabled(McpInputs.bool(patch.get("enabled")));
        }
        if (patch.containsKey("authHeaders")) {
            server.setAuthHeaders(headersJson(tenantId, patch.get("authHeaders"), existing));
        }
        server.setUpdatedAt(OffsetDateTime.now());
        mapper().updateById(server);
        return view(server);
    }

    public void delete(UserAccount actor, long tenantId, long serverId) {
        McpServer server = owned(actor, tenantId, serverId, true);
        mapper().deleteById(server.getId());
    }

    /** 用未保存的表单配置探测连接；鉴权头值必须显式提供。 */
    public McpViews.TestResult test(UserAccount actor, long tenantId, McpRequests.ServerInput input) {
        identity.tenant(actor, tenantId, true);
        Map<String, String> headers = new LinkedHashMap<>();
        if (input.authHeaders() != null) {
            for (McpRequests.Header header : input.authHeaders()) {
                String name = McpInputs.headerName(header.name());
                String value = McpInputs.headerValue(header.value());
                if (value.isBlank()) {
                    throw McpInputs.invalid("未保存配置的连接测试必须提供鉴权头明文值");
                }
                headers.put(name, value);
            }
        }
        String transport = McpInputs.transport(input.transport());
        return probeServer(
                transport,
                input.url(),
                input.command(),
                isStdio(transport) ? McpInputs.args(input.args()) : List.of(),
                isStdio(transport) ? McpInputs.envVars(input.envVars()) : List.of(),
                headers);
    }

    /** 用已保存配置探测连接；密文在服务端解密，不经过客户端。 */
    public McpViews.TestResult testSaved(UserAccount actor, long tenantId, long serverId) {
        McpServer server = owned(actor, tenantId, serverId, true);
        Map<String, String> headers = new LinkedHashMap<>();
        storedHeaders(server.getAuthHeaders())
                .forEach((name, ciphertext) -> headers.put(name, cipher.decrypt(tenantId, ciphertext)));
        return probeServer(
                server.getTransport(),
                server.getUrl(),
                server.getCommand(),
                deserialize(server.getArgs()),
                deserialize(server.getEnvVars()),
                headers);
    }

    private McpViews.TestResult probeServer(
            String transport,
            String url,
            String command,
            List<String> args,
            List<String> envVars,
            Map<String, String> headers) {
        if (isStdio(transport) && !properties.stdioEnabled()) {
            return McpViews.TestResult.failed("stdio 总开关未启用（loopcraft.mcp.stdio.enabled=false），不能启动本地进程");
        }
        if (isStdio(transport)) {
            McpInputs.command(command);
        } else {
            McpInputs.url(url);
        }
        return probe.probe(transport, url, command, args, envVars, headers);
    }

    /** 创建时一次性写入全部输入字段；鉴权头必须提供明文值。 */
    private void apply(McpServer server, McpRequests.ServerInput input) {
        String transport = McpInputs.transport(input.transport());
        server.setName(McpInputs.text(input.name(), "名称", 80));
        server.setSlug(McpInputs.slug(input.slug()));
        server.setTransport(transport);
        server.setUrl(isStdio(transport) ? null : McpInputs.url(input.url()));
        server.setCommand(isStdio(transport) ? McpInputs.command(input.command()) : null);
        server.setArgs(isStdio(transport) ? serialize(McpInputs.args(input.args())) : null);
        server.setEnvVars(isStdio(transport) ? serialize(McpInputs.envVars(input.envVars())) : null);
        server.setToolTimeoutMs(McpInputs.toolTimeoutMs(input.toolTimeoutMs()));
        if (isStdio(transport) && !properties.stdioEnabled()) {
            throw McpInputs.invalid("stdio 总开关未启用（loopcraft.mcp.stdio.enabled=false），不能保存 stdio 服务");
        }
        if (input.authHeaders() != null) {
            server.setAuthHeaders(headersJson(server.getTenantId(), input.authHeaders(), Map.of()));
        }
    }

    private void requireSlugAvailable(long tenantId, String slug, Long excludeId) {
        Long count =
                mapper()
                        .selectCount(
                                Wrappers.<McpServer>lambdaQuery()
                                        .eq(McpServer::getTenantId, tenantId)
                                        .eq(McpServer::getSlug, slug)
                                        .ne(excludeId != null, McpServer::getId, excludeId));
        if (count != null && count > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "slug 已被当前租户的其他服务使用");
        }
    }

    /** 鉴权头清单整体替换：同名留空沿用原密文，新名称必须提供明文值；空清单即清除全部。 */
    private String headersJson(long tenantId, Object value, Map<String, String> existing) {
        if (value == null) {
            throw McpInputs.invalid("鉴权头字段必须为数组，空数组表示清除");
        }
        if (!(value instanceof List<?> rows) || rows.size() > 16) {
            throw McpInputs.invalid("鉴权头必须为最多 16 项的数组");
        }
        Set<String> names = new LinkedHashSet<>();
        ArrayNode array = json.createArrayNode();
        for (Object row : rows) {
            String name;
            String plainValue;
            if (row instanceof McpRequests.Header header) {
                name = McpInputs.headerName(header.name());
                plainValue = header.value();
            } else if (row instanceof Map<?, ?> map && map.get("name") != null) {
                name = McpInputs.headerName(map.get("name"));
                plainValue = map.get("value") == null ? "" : McpInputs.headerValue(map.get("value"));
            } else {
                throw McpInputs.invalid("鉴权头项必须为包含 name 的对象");
            }
            if (!names.add(name)) {
                throw McpInputs.invalid("鉴权头名称重复：" + name);
            }
            String ciphertext;
            if (plainValue != null && !plainValue.isBlank()) {
                ciphertext = cipher.encrypt(tenantId, plainValue);
            } else if (existing.containsKey(name)) {
                ciphertext = existing.get(name);
            } else {
                throw McpInputs.invalid("鉴权头 " + name + " 缺少值，留空仅适用于已存在的同名头");
            }
            ObjectNode node = array.addObject();
            node.put("name", name);
            node.put("ciphertext", ciphertext);
        }
        return array.toString();
    }

    /** 读取存量鉴权头 {名称: 密文}；坏数据按配置错误拒绝，不静默忽略。 */
    private Map<String, String> storedHeaders(String stored) {
        Map<String, String> result = new HashMap<>();
        if (stored == null || stored.isBlank()) {
            return result;
        }
        var rows = json.readTree(stored);
        if (!rows.isArray()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "鉴权头存储格式损坏");
        }
        for (var row : rows) {
            String name = row.path("name").asString(null);
            String ciphertext = row.path("ciphertext").asString(null);
            if (name == null || ciphertext == null) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "鉴权头存储格式损坏");
            }
            result.put(name, ciphertext);
        }
        return result;
    }

    private McpViews.Server view(McpServer server) {
        List<McpViews.Header> headers = new ArrayList<>();
        storedHeaders(server.getAuthHeaders()).keySet().stream().sorted().forEach(name -> headers.add(new McpViews.Header(name)));
        return new McpViews.Server(
                server.getId(),
                server.getName(),
                server.getSlug(),
                server.getTransport(),
                server.getUrl(),
                List.copyOf(headers),
                server.getCommand(),
                deserialize(server.getArgs()),
                deserialize(server.getEnvVars()),
                server.getToolTimeoutMs(),
                Boolean.TRUE.equals(server.getEnabled()),
                server.getCreatedAt(),
                server.getUpdatedAt());
    }

    private List<String> deserialize(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        var rows = json.readTree(stored);
        if (!rows.isArray()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "数组字段存储格式损坏");
        }
        List<String> result = new ArrayList<>();
        rows.forEach(row -> result.add(row.asString()));
        return List.copyOf(result);
    }

    private String serialize(List<String> values) {
        ArrayNode array = json.createArrayNode();
        values.forEach(array::add);
        return array.toString();
    }

    private boolean isStdio(String transport) {
        return "STDIO".equals(transport);
    }

    private McpServer owned(UserAccount actor, long tenantId, long serverId, boolean manage) {
        identity.tenant(actor, tenantId, manage);
        McpServer server =
                mapper().selectOne(
                        Wrappers.<McpServer>lambdaQuery()
                                .eq(McpServer::getTenantId, tenantId)
                                .eq(McpServer::getId, serverId));
        if (server == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前租户无此 MCP 服务");
        }
        return server;
    }

    private McpServerMapper mapper() {
        return mappers.getObject();
    }
}
