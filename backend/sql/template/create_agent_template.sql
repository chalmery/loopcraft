CREATE TABLE lc_agent_template (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES lc_tenant(id) ON DELETE RESTRICT,
    name varchar(80) NOT NULL CHECK (name = trim(name) AND length(name) > 0),
    description varchar(300) NOT NULL DEFAULT '',
    enabled boolean NOT NULL DEFAULT true,
    model_id bigint NOT NULL,
    system_prompt text NOT NULL DEFAULT '' CHECK (length(system_prompt) <= 20000),
    temperature numeric CHECK (temperature >= 0 AND temperature <= 100),
    context_budget_tokens bigint CHECK (context_budget_tokens > 0 AND context_budget_tokens <= 9007199254740991),
    summary_model_id bigint,
    keep_recent_messages integer NOT NULL DEFAULT 10 CHECK (keep_recent_messages > 0),
    max_iterations bigint NOT NULL DEFAULT 0 CHECK (max_iterations >= 0 AND max_iterations <= 9007199254740991),
    timeout_minutes numeric(18,6) NOT NULL DEFAULT 0 CHECK (timeout_minutes >= 0),
    revision bigint NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_by bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name),
    FOREIGN KEY (tenant_id, model_id) REFERENCES lc_llm_model(tenant_id, id) ON DELETE RESTRICT,
    FOREIGN KEY (tenant_id, summary_model_id) REFERENCES lc_llm_model(tenant_id, id) ON DELETE RESTRICT
);
CREATE INDEX agent_template_tenant_updated_idx ON lc_agent_template(tenant_id, updated_at DESC, id DESC);
COMMENT ON TABLE lc_agent_template IS '租户共享 Agent 模板配置';
COMMENT ON COLUMN lc_agent_template.id IS '模板 ID';
COMMENT ON COLUMN lc_agent_template.tenant_id IS '所属租户';
COMMENT ON COLUMN lc_agent_template.name IS '租户内唯一名称，区分大小写';
COMMENT ON COLUMN lc_agent_template.description IS '选填用途描述';
COMMENT ON COLUMN lc_agent_template.enabled IS '是否允许用于新任务';
COMMENT ON COLUMN lc_agent_template.model_id IS '运行模型的平台记录 ID';
COMMENT ON COLUMN lc_agent_template.system_prompt IS '纯文本系统提示词';
COMMENT ON COLUMN lc_agent_template.temperature IS '显式温度，空则不发送该参数';
COMMENT ON COLUMN lc_agent_template.context_budget_tokens IS '输入加输出预留预算，空则跟随模型上限';
COMMENT ON COLUMN lc_agent_template.summary_model_id IS '摘要模型，空则跟随运行模型';
COMMENT ON COLUMN lc_agent_template.keep_recent_messages IS '保留最近消息条数，工具调用与结果成组保留';
COMMENT ON COLUMN lc_agent_template.max_iterations IS '主模型调用次数上限，0 不限制';
COMMENT ON COLUMN lc_agent_template.timeout_minutes IS '运行时间上限，单位分钟，0 不限制';
COMMENT ON COLUMN lc_agent_template.revision IS '并发控制修订号，不是模板发布版本';
COMMENT ON COLUMN lc_agent_template.created_by IS '创建者用户 ID';
COMMENT ON COLUMN lc_agent_template.created_at IS '创建时间';
COMMENT ON COLUMN lc_agent_template.updated_at IS '更新时间';
