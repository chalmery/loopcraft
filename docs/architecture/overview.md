# 系统架构总览

## 项目目标

LoopCraft 是用于学习和构建云端 ReAct Agent 的全栈项目。第一阶段关注文本模型、工具、观察结果组成的 Agent 执行循环；循环实现应保持清楚、显式并由项目自身掌控。

## 当前仓库结构

- `backend/`：Spring Boot 后端，负责未来的 HTTP API、模型接入和 Agent 执行能力。Java 包根路径为 `io.github.chalmery.loopcraft`。
- `frontend/`：React 前端，负责未来的工作台界面。
- `docs/`：长期架构/决策说明和复杂需求提案。

## 当前实现状态

当前后端和前端仍是初始化骨架，尚未实现 ReAct 执行流程、业务 API、持久化或 MCP 能力。不要把规划中的模块描述为已经存在的实现。

实际技术版本、启动命令和运行配置以 [`README.md`](../../README.md)、后端 [`pom.xml`](../../backend/pom.xml)、后端 [`application.yml`](../../backend/src/main/resources/application.yml) 及前端 [`package.json`](../../frontend/package.json) 为准。本页只维护跨目录的系统视图，不复制详细依赖配置。

## 未来能力边界

后续实现按具体需求逐步形成能力边界。后端按能力分包；例如 MCP 客户端、连接生命周期、能力发现及 MCP 工具适配集中在 `mcp` 包。前端按页面/业务功能组织组件、Hook、类型与请求逻辑。创建这些实现包和模块时遵循前后端各自的 `AGENTS.md`，不预建空模块。
