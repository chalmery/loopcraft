CREATE TABLE lc_template_request (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id bigint NOT NULL REFERENCES lc_tenant(id) ON DELETE RESTRICT,
    actor_id bigint NOT NULL,
    request_id varchar(36) NOT NULL,
    digest varchar(64) NOT NULL,
    response_json text NOT NULL,
    UNIQUE (tenant_id, actor_id, request_id)
);
COMMENT ON TABLE lc_template_request IS '模板保存幂等记录，不作为模板版本历史';
COMMENT ON COLUMN lc_template_request.id IS '记录 ID';
COMMENT ON COLUMN lc_template_request.tenant_id IS '所属租户';
COMMENT ON COLUMN lc_template_request.actor_id IS '提交者用户 ID';
COMMENT ON COLUMN lc_template_request.request_id IS '客户端保存请求 UUID';
COMMENT ON COLUMN lc_template_request.digest IS '操作与完整有效载荷的 SHA256';
COMMENT ON COLUMN lc_template_request.response_json IS '相同请求重试时返回的原始响应，包含模板配置';
