# 数据库表结构总览

本文件是 `backend/sql/` 的结构总览，便于讨论需求时快速查看表关系；列定义、约束与注释以 `backend/sql/<模块>/` 中的建表文件为准。新增或修改表结构时同步更新本文件。

## ER 图

关系线标注外键行为；每表只列主键、唯一键、外键列和关键业务列，完整列定义见对应 SQL 文件。

```mermaid
erDiagram
    lc_user {
        bigint id PK
        varchar username UK
        boolean platform_admin "平台管理员"
        boolean enabled
    }
    lc_tenant {
        bigint id PK
        varchar name
    }
    lc_tenant_member {
        bigint tenant_id PK, FK
        bigint user_id PK, FK
        varchar role "OWNER | MEMBER，每租户仅一个 OWNER"
    }
    lc_llm_source {
        bigint id PK
        bigint tenant_id PK, FK
        varchar name "租户内唯一"
        varchar protocol "OPENAI_COMPATIBLE | ANTHROPIC_COMPATIBLE"
        varchar base_url
        boolean enabled
    }
    lc_llm_model {
        bigint id PK
        bigint tenant_id PK, FK
        bigint source_id FK
        varchar model_id "供应商模型 ID，来源内唯一"
        boolean enabled
    }
    lc_skill {
        bigint id PK
        bigint tenant_id PK, FK
        varchar slug "租户内唯一（未删除时）"
        boolean enabled
        boolean deleted "软删除"
        integer latest_version
    }
    lc_skill_version {
        bigint id PK
        bigint tenant_id PK, FK
        bigint skill_id FK
        integer number "技能内版本号，唯一"
        varchar bucket "版本整包对象"
        varchar object_key
    }
    lc_skill_tag {
        bigint id PK
        bigint tenant_id PK, FK
        bigint skill_id FK
        varchar name "技能内唯一"
    }
    lc_skill_object_upload {
        bigint id PK
        varchar bucket "独立上传跟踪，无外键"
        varchar object_key "bucket+key 唯一"
    }
    lc_mcp_server {
        bigint id PK
        bigint tenant_id PK, FK
        varchar slug "工具名前缀标识，租户内唯一"
        varchar transport "HTTP_STREAMABLE | HTTP_SSE | STDIO"
        varchar url "远程服务地址，HTTP 两类必填"
        text auth_headers "鉴权头 JSON，值为密文不回显"
        varchar command "stdio 命令"
        integer tool_timeout_ms "空值用全局 toolTimeout"
        boolean enabled
    }
    lc_agent_template {
        bigint id PK
        bigint tenant_id PK, FK
        varchar name "租户内唯一"
        boolean enabled
        bigint model_id FK "运行模型"
        bigint summary_model_id FK "会话压缩用，首版不消费"
        bigint max_iterations "运行预算，来自模板"
        numeric timeout_minutes
        bigint revision "乐观锁"
    }
    lc_template_skill {
        bigint id PK
        bigint tenant_id PK, FK
        bigint template_id FK "ON DELETE CASCADE"
        bigint skill_id FK
        integer version_number "锁定的技能版本"
        integer position
    }
    lc_template_request {
        bigint id PK
        bigint tenant_id PK, FK
        bigint actor_id "无外键，请求发起人"
        varchar request_id "幂等键，租户+人+请求唯一"
    }
    lc_agent_run {
        varchar id PK "UUID"
        bigint tenant_id FK
        bigint template_id "无外键，运行时的模板快照"
        varchar status "RUNNING 或六种终态"
        varchar failure "FAILED 原因码"
        text answer "仅 COMPLETED 非空"
        integer iterations
        integer tool_calls
    }
    lc_agent_run_event {
        bigint id PK "事件序号，同时作 SSE 事件 ID"
        varchar run_id FK "ON DELETE CASCADE"
        varchar type "run_started/iteration/tool_* /run_finished"
        text payload "JSON，不含工具参数与结果内容"
    }

    lc_user ||--o{ lc_tenant_member : "加入"
    lc_tenant ||--o{ lc_tenant_member : "成员"
    lc_tenant ||--o{ lc_llm_source : "供应商连接"
    lc_llm_source ||--o{ lc_llm_model : "模型目录"
    lc_tenant ||--o{ lc_skill : "技能"
    lc_skill ||--o{ lc_skill_version : "整包版本"
    lc_skill ||--o{ lc_skill_tag : "标签"
    lc_tenant ||--o{ lc_mcp_server : "MCP 服务"
    lc_tenant ||--o{ lc_agent_template : "模板"
    lc_llm_model ||--o{ lc_agent_template : "运行模型 / 摘要模型"
    lc_agent_template ||--o{ lc_template_skill : "绑定技能 CASCADE"
    lc_skill ||--o{ lc_template_skill : "被绑定 RESTRICT"
    lc_skill_version ||--o{ lc_template_skill : "锁定版本 RESTRICT"
    lc_tenant ||--o{ lc_template_request : "幂等请求记录"
    lc_tenant ||--o{ lc_agent_run : "Agent 运行"
    lc_agent_run ||--o{ lc_agent_run_event : "事件流 CASCADE"
```

## 表清单

| 模块 | 表 | 用途 | 建表文件 |
| --- | --- | --- | --- |
| identity | lc_user | 平台账号，密码单向哈希 | `backend/sql/identity/create_user.sql` |
| identity | lc_tenant | 租户 | `backend/sql/identity/create_tenant.sql` |
| identity | lc_tenant_member | 租户成员关系，每租户一个负责人 | `backend/sql/identity/create_tenant_member.sql` |
| llm | lc_llm_source | 租户供应商连接（协议、baseUrl、加密 API Key） | `backend/sql/llm/create_llm_source.sql` |
| llm | lc_llm_model | 来源下的模型目录（能力字段见 add_model_capabilities.sql） | `backend/sql/llm/create_llm_model.sql` |
| skill | lc_skill | 技能元数据，软删除 | `backend/sql/skill/create_skill.sql` |
| skill | lc_skill_version | 技能整包版本，指向对象存储 | `backend/sql/skill/create_skill_version.sql` |
| skill | lc_skill_tag | 技能标签 | `backend/sql/skill/create_skill_tag.sql` |
| skill | lc_skill_object_upload | 上传对象跟踪（清理补偿用，无外键） | `backend/sql/skill/create_skill_object_upload.sql` |
| mcp | lc_mcp_server | 租户 MCP 服务配置（传输、地址/命令、加密鉴权头、超时覆盖） | `backend/sql/mcp/create_mcp_server.sql` |
| template | lc_agent_template | Agent 运行模板（模型、提示词、预算） | `backend/sql/template/create_agent_template.sql` |
| template | lc_template_skill | 模板与技能版本的绑定 | `backend/sql/template/create_template_skill.sql` |
| template | lc_template_request | 模板保存幂等请求记录 | `backend/sql/template/create_template_request.sql` |
| agent | lc_agent_run | Agent 运行记录与终态结果；Redis Stream 仅作实时热通道 | `backend/sql/agent/create_agent_run.sql` |
| agent | lc_agent_run_event | 运行事件流，SSE 断线重放的兜底数据源 | `backend/sql/agent/create_agent_run_event.sql` |

## 初始化顺序

按外键依赖：identity（lc_user → lc_tenant → lc_tenant_member）→ llm（lc_llm_source → lc_llm_model）→ skill（lc_skill → lc_skill_version / lc_skill_tag / lc_skill_object_upload）→ mcp（lc_mcp_server）→ template（lc_agent_template → lc_template_skill / lc_template_request）→ agent（lc_agent_run → lc_agent_run_event）。各模块内顺序见 `backend/sql/<模块>/README.md`。

Agent 运行事件同时写入 lc_agent_run_event（真相源）与 Redis Stream（实时通道，终态后短时保留）；SSE 重放优先读 Stream，缺失或过期时以本表兜底。
