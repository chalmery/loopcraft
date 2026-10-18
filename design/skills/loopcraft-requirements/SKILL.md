---
name: loopcraft-requirements
description: Discuss and specify a substantial new LoopCraft feature through dependency-aware questions and an evolving interactive prototype. Produce an implementation-ready conclusion.md and a standalone prototype outside frontend. Use for LoopCraft requirement discovery, page design, requirement review, or development handoff; ordinary fixes do not require this workflow.
---

# LoopCraft 需求共创

借鉴 Matt Pocock 的 [grill-me / grilling](https://github.com/mattpocock/skills/tree/main/skills/productivity/grilling) 的决策树提问方法，增加 LoopCraft 的项目核查、边讨论边设计和开发交接产物。此技能自包含，不要求另外调用 Skill 工具或安装 grilling，也不修改上游技能。

## 固定交付与存储

一个需求一个目录，默认位于仓库根目录的 `design/requirements/<slug>/`，固定交付：

- `conclusion.md`：本轮有效讨论结论、业务规则和完整开发交接；让新 Agent 不读聊天记录也能开发。
- `prototype/`：可以直接预览、点击的独立页面，入口 `index.html`。源码、样式和示例数据都放在这个目录。

原型不得进入 `frontend/src`、正式路由、生产构建或后端。默认使用无远程依赖的 HTML/CSS/JavaScript；复杂需求可使用独立工程，但不能通过运行时导入、node_modules 链接或软链接依赖正式 frontend。原型仅操作内存示例数据，不接业务接口、真实模型和真实凭证，不将 API Key 存入浏览器持久存储。

原型只表达本需求的可观察流程。纯后端需求可以制作输入/结果模拟面板，不因此增加未要求的正式产品页面。

创建交接文档前读 [references/handoff-contract.md](references/handoff-contract.md)，按需使用 [assets/conclusion-template.md](assets/conclusion-template.md)。模板是写作起点，交付正文要写出确定的行为、示例和约束。

## 讨论与原型一起推进

1. 定位实际 LoopCraft checkout，核查 Git 状态、根与模块 AGENTS、相关源码、SQL、依赖和现有页面。历史记录只能帮助定位，不能当作当前实现。把“已有事实、用户确认、建议、待定”分别记录。
2. 沿决策依赖提问：一轮只问当前前置条件已明确的问题，每题给出推荐答案，并让“是 / 按建议”能直接接受。不要让用户查可以从代码、文档和日志获得的事实，也不要重复确认已经回答的选择。
3. 一旦能表达入口、主体对象和主流程，就创建第一版原型并真正打开给用户看。未决分支标注待定。不要以“先答完全部问题 / 先确认全部方案”为由推迟已经授权的原型制作；用户要先看页面时先交付可视草稿。
4. 用户反馈出现后，同时更新原型与 conclusion.md 中受影响的规则、页面状态、接口语义和验收场景。文档描述最终有效结论，不堆积聊天原文和已废弃方案。示例 UI 不能充当真实能力实现证据。
5. 展示页面时提供明确预览入口、启动命令和关键可点击流程。检查宽屏、窄屏、只读/编辑、空数据、校验反馈以及本轮涉及的错误状态。前端视觉沿用当前项目设计 Token 和信息组织，正式实现仍必须复用其 UI 组件。

## LoopCraft 适配边界

- 实际技术版本、Session/CSRF、租户角色及数据权限，以当前代码与 AGENTS 为准；不在技能中固化易过期版本。
- 涉及业务数据时明确租户归属、读写角色、服务端校验、跨租户访问结果；新增权限不得顺手改变已有身份/成员接口规则。
- 后端设计按能力分包；普通 CRUD、分页优先 MyBatis-Plus；每表独立手动 SQL 与简短中文注释。前端按 feature 组织并复用现有工作区、表单、通知和组件。
- Agent 循环仍遵守用户亲自实现的项目目标。需求未授权的框架接管、协议、工具或平台功能不能因讨论而扩展进来。
- 输出中区分当前交付阶段与最终产品范围。授权讨论/原型不等于授权正式实现、发布或提交；已经授权的文档与原型应直接完成，不增加阶段确认。

## 可以交给开发 Agent 的标准

`conclusion.md` 显式给出 `draft`、`review` 或 `ready-for-development` 状态。页面未评审、关键产品决策待定时保持 review/draft；不得编造用户确认。用户已经接受的决定不再重复索要确认。

只有业务关键分支、数据与权限约束、接口输入输出/错误、页面交互及验收均可据此实施，且页面已有用户认可的证据，才标记 ready-for-development。不影响产品含义的内部实现细节留给开发 Agent 判断，避免无限追问。

交接时给开发 Agent 一段可直接使用的指令，写明两个产物的仓库相对路径、实际代码入口、要遵守的 AGENTS、未决项以及允许实施的范围。开发 Agent 应先检查当前 checkout 与交接基线的差异；发现设计冲突时更新结论并说明，不能静默照搬过期代码或原型代码。
