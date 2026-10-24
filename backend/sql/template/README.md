# Agent 模板 SQL

依赖 identity 的用户、租户、成员表，llm 的连接/模型表，以及 skill 的基本信息和内容版本表。先完成各模块已有脚本，再按顺序执行以下增量：

```bash
./scripts/dev.sh db sql llm/add_model_capabilities.sql
./scripts/dev.sh db sql template/create_agent_template.sql
./scripts/dev.sh db sql template/create_template_skill.sql
./scripts/dev.sh db sql template/create_template_request.sql
```

已有数据库只执行尚未应用的文件；应用不会自动执行 SQL。新增模型能力列保持空值，不给旧模型填入猜测的上下文上限。在模型接入补齐能力后，模板可用性会随之变化。

模板表使用同租户组合外键引用主模型与摘要模型。绑定表同租户引用 Skill，并固定引用其内容版本；删除模型/连接或被引用的 Skill 版本将被外键拒绝。Skill 软删除在持有 Skill 行锁期间先检查模板绑定，防止对象后台清理先于引用保护。删除模板级联移除绑定，不删除模型或 Skill。

`lc_template_request` 保存同租户、操作者、请求标识绑定的摘要和原始响应，用于网络失败重试，不开放历史版本查询。不能单独删除活跃请求幂等记录后自动重试旧请求。该表随租户业务数据一起维护，禁止在日志输出其模板提示词内容。
