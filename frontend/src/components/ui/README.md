# 基础 UI 组件

本目录组件由 [shadcn/ui 官方 registry](https://ui.shadcn.com/docs/installation/manual) 的 `new-york-v4` 组件生成，并按 LoopCraft 的浅色语义 Token、字体、间距与圆角定制。交互原语使用 Radix，通用图标使用 Lucide；上游许可见 [LICENSE](LICENSE)。

生成源码由项目维护。新增组件时审阅依赖与导入，使用 `@/lib/utils` 的 `cn`，不覆盖现有定制；将颜色映射到 `src/app/globals.css`，按前端 [AGENTS.md](../../../AGENTS.md) 检查键盘、焦点和响应式行为。
