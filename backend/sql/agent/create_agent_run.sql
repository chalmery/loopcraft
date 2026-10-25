CREATE TABLE lc_agent_run (
    id varchar(36) PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES lc_tenant(id) ON DELETE RESTRICT,
    template_id bigint NOT NULL,
    created_by bigint NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED', 'ITERATION_LIMIT', 'TOOL_CALL_LIMIT', 'TIMED_OUT', 'CANCELLED', 'FAILED')),
    failure varchar(32),
    answer text,
    iterations integer NOT NULL DEFAULT 0,
    tool_calls integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamptz
);
CREATE INDEX agent_run_tenant_created_idx ON lc_agent_run(tenant_id, created_at DESC, id);
COMMENT ON TABLE lc_agent_run IS 'Agent 运行记录；事件与结果持久层，Redis Stream 仅作实时热通道';
COMMENT ON COLUMN lc_agent_run.id IS '运行 ID（UUID），受理时生成';
COMMENT ON COLUMN lc_agent_run.tenant_id IS '所属租户';
COMMENT ON COLUMN lc_agent_run.template_id IS '运行使用的模板 ID';
COMMENT ON COLUMN lc_agent_run.created_by IS '发起用户 ID';
COMMENT ON COLUMN lc_agent_run.status IS '运行状态：RUNNING 或终态 COMPLETED/ITERATION_LIMIT/TOOL_CALL_LIMIT/TIMED_OUT/CANCELLED/FAILED';
COMMENT ON COLUMN lc_agent_run.failure IS 'FAILED 时的原因码：MODEL_ERROR/INVALID_MODEL_RESPONSE/OUTPUT_LIMIT/CONTEXT_LIMIT/TOOL_EXECUTION_UNCERTAIN';
COMMENT ON COLUMN lc_agent_run.answer IS '最终答案，仅 COMPLETED 时非空';
COMMENT ON COLUMN lc_agent_run.iterations IS '已发起的模型请求数';
COMMENT ON COLUMN lc_agent_run.tool_calls IS '模型提出的工具调用总数（含失败回填）';
COMMENT ON COLUMN lc_agent_run.created_at IS '受理时间';
COMMENT ON COLUMN lc_agent_run.finished_at IS '终态时间';
