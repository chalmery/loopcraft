# LoopCraft

LoopCraft 是一个用于学习和构建云端 Agent 的全栈项目。当前仓库仅包含 Spring Boot / Spring AI 与 React / Vite 的初始化骨架，尚未实现 Agent 执行流程或业务功能。

## 目录

- `backend/`：Java 25、Spring Boot 4.1.1、Spring AI 2.0.1、Maven。Java 包根路径：`io.github.chalmery.loopcraft`。
- `frontend/`：React、TypeScript、Vite、Bun、Tailwind CSS。

## 启动

后端：

```bash
cd backend
./mvnw spring-boot:run
```

前端：

```bash
cd frontend
bun install
bun run dev
```

默认配置不需要模型凭证。准备试用 Spring AI OpenAI ChatModel 时，先复制 `backend/.env.example` 为 `backend/.env`，填写 API Key、OpenAI 兼容的 API 根地址和模型 ID，再显式加载变量并启用 `llm` Profile。操作说明和配置边界见 [`docs/development.md`](docs/development.md)。

ReAct 执行循环、工具和产品 API 尚未实现；模型配置只装配单次调用所用的 `ChatModel`，循环仍由项目代码负责。

## AI 开发与检查

仓库约定和本地验证命令见 [`AGENTS.md`](AGENTS.md)。第一次克隆后执行 `git config core.hooksPath .githooks` 安装快速提交检查；检查完整仓库可运行 `./scripts/check.sh all`。GitHub Actions 会在推送和 Pull Request 上执行同样的分模块检查。

首次前端安装在 `frontend/` 中执行 `bun install`；后续 CI 使用 `bun install --frozen-lockfile`。

## Git 提交检查

仓库包含一个轻量 `pre-commit` Hook，会检查暂存内容的空白错误和常见凭证格式。当前工作区已启用；克隆仓库后，可运行下面的命令启用：

```bash
git config core.hooksPath .githooks
```

该检查是基础防护，不替代完整的密钥扫描。
