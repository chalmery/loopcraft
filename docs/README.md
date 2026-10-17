# LoopCraft 文档

文档按用途分层，避免重复记录同一事实：

- [`../AGENTS.md`](../AGENTS.md)、[`../backend/AGENTS.md`](../backend/AGENTS.md)、[`../frontend/AGENTS.md`](../frontend/AGENTS.md)：长期有效的协作、代码结构和项目边界规则。
- [`architecture/`](architecture/)：当前系统结构、能力边界和关键流程。代码与配置是实现现状的最终依据。
- [`decisions/`](decisions/)：需要长期追溯的技术决策及其背景和取舍。
- [`changes/`](changes/)：跨模块、含重要设计选择或分阶段实施的需求提案。小型修改不需要单独建目录。

新增文档时只记录不能从代码、配置或现有文档直接看出的上下文。标明事实、已确认决策和计划之间的区别；架构实现变化后同步更新对应文档。
