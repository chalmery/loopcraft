# LoopCraft 前端

基于 Next.js 16 App Router、React 19、TypeScript、Tailwind CSS 4、shadcn/ui（Radix）和 Lucide。使用 Bun 管理依赖，Node.js 运行 Next.js。登录页及身份工作台使用服务端渲染；浏览器中的表单操作通过同源 `/api` 路径访问 Spring Boot。需要 Node.js 20.9 或更新版本。

## 开发

```bash
bun install --frozen-lockfile
```

从仓库根目录运行 `./scripts/dev.sh up` 同时启动前后端。当前仅提供 dev 运行环境。固定默认地址为 `http://127.0.0.1:5173` 和 `http://127.0.0.1:8080`，仓库级启动器按 `SERVER_PORT` 设置后端代理地址。需要修改端口时，在 `env/dev/.env` 中显式设置 `FRONTEND_PORT` 或 `SERVER_PORT`；应用运行期间不要再用另一条命令启动同一个服务。

## 检查、构建与运行

```bash
bun run format       # 格式化并排序 Tailwind 类
bun run check        # 格式检查 + Oxlint + Next.js 路由类型生成 + tsc
bun run test         # 成员分页、会话失效与 CSRF 恢复回归测试
bun run build
bun run start
```

Next.js 构建包含 SSR 服务，需要 Node.js，不能作为纯静态站点运行。`build` / `start` 用于本地验证构建产物，后端默认仍为 `http://127.0.0.1:8080`；若手动设置 `BACKEND_URL`，构建与启动必须使用相同值，因为 `/api` 代理目标在构建时写入产物。日常开发使用仓库启动器即可。

仓库根目录的 `./scripts/check.sh frontend` 执行 `check`、回归测试和生产构建，CI 使用同一入口。`bun run lint:fix` 只修复可自动修复的 lint 问题；`bun run typecheck` 可单独生成路由类型并检查 TypeScript，首次使用也不依赖已有 `.next` 产物。

## 目录与 UI

- `src/app/`：Next.js 路由、根布局、SSR 页面和全局主题。
- `src/features/auth/`：登录与会话。
- `src/features/profile/`：个人资料和右上角账户菜单。
- `src/features/workspace/`：租户选择、工作区成员和租户请求。
- `src/features/admin/`：租户管理与账号管理，独立分页列表、右侧栏新增/编辑和删除确认。
- `src/features/portal/`：登录、租户选择和后台各功能之间的页面流程组合。
- `src/components/ui/`：从 shadcn 官方 registry 按需引入并定制的基础组件。
- `src/components/layout/`：跨业务页面使用的品牌与布局组件。
- `src/lib/utils.ts`：`cn` 合并条件类名与 Tailwind 覆盖。
- `src/lib/api.ts`：同源 API 请求、CSRF 与响应结构校验。

颜色与尺寸遵循 [AGENTS.md](AGENTS.md)。Tailwind 4 主题在 `src/app/globals.css` 中维护，使用语义类，如 `bg-canvas`、`bg-surface`、`text-foreground`、`text-muted-foreground`。通用图标从 `lucide-react` 具名导入。

需要新控件时，在本目录执行 `bunx shadcn@latest add <组件名>`，审查生成源码后再使用；不要覆盖现有组件。新组件需适配项目色板与尺度，`cn` 统一从 `@/lib/utils` 导入。源码由本项目维护，不会随依赖升级自动同步。

## 后台配置

导航分组为「后台配置」。平台管理员可进入「租户管理」「账号管理」，无需先选中租户；「工作区与成员」仅向当前租户负责人开放。普通成员通过账户菜单进入个人设置。

两个管理列表使用服务端分页，默认每页 10 条，暂不提供搜索条件。顶部左侧新增、右侧刷新；行末编辑打开右侧栏，删除需二次确认。表单失败保留输入并显示错误，操作成功显示全局通知。

账号编辑支持用户名、显示名称和启停状态；不提供平台权限提升。平台管理员不能删除或停用，负责人账号需先交接租户。租户编辑支持名称与负责人，原负责人交接后成为普通成员；存在模型来源等关联业务数据时不能直接删除租户。

## Git hooks

克隆后在仓库根目录执行 `git config core.hooksPath .githooks`。沿用原生 Git hooks，不额外引入 Husky：

- `pre-commit`：已有仓库内容检查；有前端变更时，将 Git 暂存文件与配置导出到临时目录，运行 Prettier 和 Oxlint。只从工作区复用已安装依赖，不改写源码或暂存区。
- `pre-push`：非删除推送运行当前工作区的完整前端检查和构建。构建前先保存文件；CI 会再检查实际推送版本。

React Hooks、Server/Client 边界和可访问性规范见 [AGENTS.md](AGENTS.md)。
