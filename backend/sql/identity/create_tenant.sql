CREATE TABLE lc_tenant (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name varchar(100) NOT NULL CHECK (length(trim(name)) > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE lc_tenant IS '租户：独立工作空间';
COMMENT ON COLUMN lc_tenant.id IS '租户 ID，后续业务数据据此隔离';
COMMENT ON COLUMN lc_tenant.name IS '租户名称';
COMMENT ON COLUMN lc_tenant.created_at IS '创建时间';
COMMENT ON COLUMN lc_tenant.updated_at IS '更新时间';
