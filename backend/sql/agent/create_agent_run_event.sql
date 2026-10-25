CREATE TABLE lc_agent_run_event (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id varchar(36) NOT NULL REFERENCES lc_agent_run(id) ON DELETE CASCADE,
    type varchar(32) NOT NULL,
    payload text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX agent_run_event_run_seq_idx ON lc_agent_run_event(run_id, id);
COMMENT ON TABLE lc_agent_run_event IS 'Agent 运行事件流；SSE 断线重放的兜底数据源';
COMMENT ON COLUMN lc_agent_run_event.id IS '事件序号，单调递增，同时作为 SSE 事件 ID';
COMMENT ON COLUMN lc_agent_run_event.run_id IS '所属运行';
COMMENT ON COLUMN lc_agent_run_event.type IS '事件类型：run_started/iteration_started/tool_called/tool_finished/run_finished';
COMMENT ON COLUMN lc_agent_run_event.payload IS '事件数据 JSON；不含工具参数与结果内容';
COMMENT ON COLUMN lc_agent_run_event.created_at IS '产生时间';
