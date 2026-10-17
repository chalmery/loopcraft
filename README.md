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

模型凭证、数据库、API、ReAct 循环和产品界面将在后续开发中加入。

## Git 提交检查

仓库包含一个轻量 `pre-commit` Hook，会检查暂存内容的空白错误和常见凭证格式。当前工作区已启用；克隆仓库后，可运行下面的命令启用：

```bash
git config core.hooksPath .githooks
```

该检查是基础防护，不替代完整的密钥扫描。
