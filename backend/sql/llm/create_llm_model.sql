CREATE TABLE lc_llm_model (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL,
    source_id bigint NOT NULL,
    name varchar(80) NOT NULL CHECK (length(trim(name)) > 0),
    model_id varchar(160) NOT NULL CHECK (length(trim(model_id)) > 0 AND model_id = trim(model_id)),
    origin varchar(16) NOT NULL CHECK (origin IN ('MANUAL', 'DISCOVERY')),
    enabled boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT llm_model_source_tenant_fk FOREIGN KEY (tenant_id, source_id) REFERENCES lc_llm_source(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT llm_model_source_model_unique UNIQUE (source_id, model_id)
);
CREATE INDEX llm_model_tenant_source_updated_idx ON lc_llm_model (tenant_id, source_id, updated_at DESC, id DESC);
COMMENT ON TABLE lc_llm_model IS '连接下可管理的模型';
COMMENT ON COLUMN lc_llm_model.id IS '本地模型记录 ID';
COMMENT ON COLUMN lc_llm_model.tenant_id IS '所属租户，与连接一致';
COMMENT ON COLUMN lc_llm_model.source_id IS '所属供应商连接';
COMMENT ON COLUMN lc_llm_model.name IS '模型显示名称';
COMMENT ON COLUMN lc_llm_model.model_id IS '供应商实际模型 ID，区分大小写';
COMMENT ON COLUMN lc_llm_model.origin IS 'MANUAL 手动添加，DISCOVERY 查询导入';
COMMENT ON COLUMN lc_llm_model.enabled IS '模型自身是否启用';
COMMENT ON COLUMN lc_llm_model.created_at IS '创建时间';
COMMENT ON COLUMN lc_llm_model.updated_at IS '更新时间';
