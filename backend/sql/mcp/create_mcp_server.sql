CREATE TABLE lc_mcp_server (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES lc_tenant(id) ON DELETE RESTRICT,
    name varchar(80) NOT NULL CHECK (length(trim(name)) > 0 AND name = trim(name)),
    slug varchar(32) NOT NULL CHECK (slug ~ '^[a-z][a-z0-9-]{1,31}$'),
    transport varchar(20) NOT NULL CHECK (transport IN ('HTTP_STREAMABLE', 'HTTP_SSE', 'STDIO')),
    url varchar(2048),
    auth_headers text,
    command varchar(512),
    args text,
    env_vars text,
    tool_timeout_ms integer CHECK (tool_timeout_ms IS NULL OR tool_timeout_ms BETWEEN 100 AND 600000),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT mcp_server_tenant_slug_unique UNIQUE (tenant_id, slug)
);
CREATE INDEX mcp_server_tenant_updated_idx ON lc_mcp_server (tenant_id, updated_at DESC, id DESC);
COMMENT ON TABLE lc_mcp_server IS '租户 MCP 服务配置';
COMMENT ON COLUMN lc_mcp_server.id IS '服务 ID';
COMMENT ON COLUMN lc_mcp_server.tenant_id IS '所属租户';
COMMENT ON COLUMN lc_mcp_server.name IS '显示名称';
COMMENT ON COLUMN lc_mcp_server.slug IS '工具名前缀标识，租户内唯一';
COMMENT ON COLUMN lc_mcp_server.transport IS 'HTTP_STREAMABLE、HTTP_SSE 或 STDIO';
COMMENT ON COLUMN lc_mcp_server.url IS '远程服务地址，HTTP 两类必填，服务层校验';
COMMENT ON COLUMN lc_mcp_server.auth_headers IS '鉴权头 JSON 数组，值为 v1 密文，接口不回显';
COMMENT ON COLUMN lc_mcp_server.command IS 'stdio 命令，STDIO 必填，服务层校验';
COMMENT ON COLUMN lc_mcp_server.args IS 'stdio 参数 JSON 数组';
COMMENT ON COLUMN lc_mcp_server.env_vars IS 'stdio 环境变量 JSON 数组，每行 KEY=VALUE';
COMMENT ON COLUMN lc_mcp_server.tool_timeout_ms IS '工具调用超时毫秒，空值使用全局 toolTimeout';
COMMENT ON COLUMN lc_mcp_server.enabled IS '是否启用，停用后受理将拒绝';
COMMENT ON COLUMN lc_mcp_server.created_at IS '创建时间';
COMMENT ON COLUMN lc_mcp_server.updated_at IS '更新时间';
