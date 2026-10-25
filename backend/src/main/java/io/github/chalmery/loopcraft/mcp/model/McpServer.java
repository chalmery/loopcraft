package io.github.chalmery.loopcraft.mcp.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/** 租户 MCP 服务配置；鉴权头 JSON 内的值为密文，视图永不回显明文。 */
@TableName("lc_mcp_server")
public class McpServer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    private String name;

    /** 工具名前缀标识，租户内唯一。 */
    private String slug;

    /** HTTP_STREAMABLE、HTTP_SSE 或 STDIO。 */
    private String transport;

    private String url;

    /** JSON 数组 [{"name":..., "ciphertext":...}]，空值表示未配置。 */
    private String authHeaders;

    private String command;

    /** JSON 数组，stdio 参数。 */
    private String args;

    /** JSON 数组，每行 KEY=VALUE。 */
    private String envVars;

    /** 工具调用超时毫秒，空值使用全局 toolTimeout。 */
    private Integer toolTimeoutMs;

    private Boolean enabled;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getAuthHeaders() {
        return authHeaders;
    }

    public void setAuthHeaders(String authHeaders) {
        this.authHeaders = authHeaders;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getArgs() {
        return args;
    }

    public void setArgs(String args) {
        this.args = args;
    }

    public String getEnvVars() {
        return envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars;
    }

    public Integer getToolTimeoutMs() {
        return toolTimeoutMs;
    }

    public void setToolTimeoutMs(Integer toolTimeoutMs) {
        this.toolTimeoutMs = toolTimeoutMs;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
