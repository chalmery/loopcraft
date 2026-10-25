# Agent 运行 SQL

依赖 identity 的租户表。先完成 identity 模块脚本，再按顺序执行：

```bash
./scripts/dev.sh db sql agent/create_agent_run.sql
./scripts/dev.sh db sql agent/create_agent_run_event.sql
```

运行记录与事件为追加型数据：run 行在受理时创建并在终态时更新一次，事件行只追加不修改。Redis Stream 中的事件仅是实时热通道，重放与查询以这两张表为准。
