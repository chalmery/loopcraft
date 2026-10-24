ALTER TABLE lc_llm_model
    ADD COLUMN context_limit bigint CHECK (context_limit > 0 AND context_limit <= 9007199254740991),
    ADD COLUMN temperature_supported boolean,
    ADD COLUMN temperature_min numeric,
    ADD COLUMN temperature_max numeric,
    ADD CONSTRAINT llm_model_tenant_id_unique UNIQUE (tenant_id, id),
    ADD CONSTRAINT llm_model_temperature_range CHECK (
        (temperature_min IS NULL AND temperature_max IS NULL) OR
        (temperature_supported IS TRUE AND temperature_min IS NOT NULL AND temperature_max IS NOT NULL
            AND temperature_min >= 0 AND temperature_max >= temperature_min
            AND temperature_max <= 100));
COMMENT ON COLUMN lc_llm_model.context_limit IS '模型最大上下文 Token 数，未知时为空';
COMMENT ON COLUMN lc_llm_model.temperature_supported IS '是否支持温度参数，未知时为空';
COMMENT ON COLUMN lc_llm_model.temperature_min IS '可配置温度下限，与上限同时维护';
COMMENT ON COLUMN lc_llm_model.temperature_max IS '可配置温度上限，与下限同时维护';
