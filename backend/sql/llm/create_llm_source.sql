CREATE TABLE lc_llm_source (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES lc_tenant(id) ON DELETE RESTRICT,
    name varchar(80) NOT NULL CHECK (length(trim(name)) > 0 AND name = trim(name)),
    supplier_name varchar(80) NOT NULL CHECK (length(trim(supplier_name)) > 0),
    protocol varchar(40) NOT NULL CHECK (protocol IN ('OPENAI_COMPATIBLE', 'ANTHROPIC_COMPATIBLE')),
    base_url varchar(2048) NOT NULL CHECK (length(trim(base_url)) > 0),
    api_key_ciphertext text,
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT llm_source_tenant_name_unique UNIQUE (tenant_id, name),
    CONSTRAINT llm_source_tenant_id_unique UNIQUE (tenant_id, id)
);
CREATE INDEX llm_source_tenant_updated_idx ON lc_llm_source (tenant_id, updated_at DESC, id DESC);
COMMENT ON TABLE lc_llm_source IS '租户供应商连接';
COMMENT ON COLUMN lc_llm_source.id IS '连接 ID';
COMMENT ON COLUMN lc_llm_source.tenant_id IS '所属租户';
COMMENT ON COLUMN lc_llm_source.name IS '租户内唯一连接名称';
COMMENT ON COLUMN lc_llm_source.supplier_name IS '自由填写的供应商名称';
COMMENT ON COLUMN lc_llm_source.protocol IS 'OPENAI_COMPATIBLE 或 ANTHROPIC_COMPATIBLE';
COMMENT ON COLUMN lc_llm_source.base_url IS '包含版本前缀的 API 根地址';
COMMENT ON COLUMN lc_llm_source.api_key_ciphertext IS 'v1 AES-256-GCM 密文，空值表示未配置';
COMMENT ON COLUMN lc_llm_source.enabled IS '是否启用，不覆盖子模型状态';
COMMENT ON COLUMN lc_llm_source.created_at IS '创建时间';
COMMENT ON COLUMN lc_llm_source.updated_at IS '更新时间';
