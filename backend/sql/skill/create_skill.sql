CREATE TABLE lc_skill (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES lc_tenant(id) ON DELETE RESTRICT,
    slug varchar(64) NOT NULL CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name varchar(80) NOT NULL CHECK (length(trim(name)) > 0),
    description varchar(1000) NOT NULL CHECK (length(trim(description)) > 0),
    origin varchar(16) NOT NULL DEFAULT 'CUSTOM' CHECK (origin IN ('SYSTEM','CUSTOM')),
    enabled boolean NOT NULL DEFAULT true,
    deleted boolean NOT NULL DEFAULT false,
    latest_version integer NOT NULL DEFAULT 0 CHECK (latest_version >= 0),
    revision bigint NOT NULL DEFAULT 0,
    creation_request varchar(36) NOT NULL,
    created_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, creation_request)
);
CREATE UNIQUE INDEX skill_active_slug_unique ON lc_skill(tenant_id, slug) WHERE NOT deleted;
CREATE INDEX skill_tenant_updated_idx ON lc_skill(tenant_id, deleted, updated_at DESC, id DESC);
COMMENT ON TABLE lc_skill IS '租户 Skill 基本信息';
COMMENT ON COLUMN lc_skill.id IS 'Skill ID';
COMMENT ON COLUMN lc_skill.tenant_id IS '所属租户';
COMMENT ON COLUMN lc_skill.slug IS '租户内唯一且不可修改的稳定标识';
COMMENT ON COLUMN lc_skill.name IS '平台显示名称，不同步包内文件';
COMMENT ON COLUMN lc_skill.description IS '平台描述，不同步包内文件';
COMMENT ON COLUMN lc_skill.origin IS 'SYSTEM 系统内置，CUSTOM 租户自建';
COMMENT ON COLUMN lc_skill.enabled IS '是否启用；禁用后不向模型提供';
COMMENT ON COLUMN lc_skill.deleted IS '是否已删除待清理';
COMMENT ON COLUMN lc_skill.latest_version IS '最新内容版本号';
COMMENT ON COLUMN lc_skill.revision IS '基本信息并发修订号';
COMMENT ON COLUMN lc_skill.creation_request IS '创建请求幂等标识';
COMMENT ON COLUMN lc_skill.created_by IS '创建用户 ID';
COMMENT ON COLUMN lc_skill.created_at IS '创建时间';
COMMENT ON COLUMN lc_skill.updated_at IS '更新时间';
