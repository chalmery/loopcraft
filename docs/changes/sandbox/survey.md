# 沙箱能力调研：LoopCraft 工具执行隔离与主流实现方式

调研日期：2026-10-25。范围：本仓库当前代码；本地 `harness-references/` 五个参考仓库中与命令执行隔离、沙箱、审批相关的文件（定向静态阅读）；OpenAI Codex 官方沙箱文档（网络调研，当日快照）。oh-my-pi 与 opencode 在该快照中没有 OS 级进程沙箱实现，本文以它们的纯审批模型作为对照。

本文回答三个问题：**当 Agent 要执行 shell/代码类工具时，主流 harness 用什么机制隔离副作用；沙箱与权限审批如何分工；LoopCraft 这种云端多租户形态在引入执行类工具前需要拍板什么。** 结论与建议均为待讨论内容，不是已批准实现。

证据边界：参考仓库为本地快照，未运行验证；各项目能力以调研当日文件为准；官方文档表述与源码不一致时以源码为准。

## 1. 核心结论

1. **「权限决定要不要执行，沙箱决定执行了能碰到什么」是各家共同的安全模型。** 权限系统（allow/ask/deny、审批模式）负责解释意图、在执行前把关；沙箱给子进程一个 OS 级能力边界，即使应用层判断漏了，后果也被压缩在边界内。沙箱的直接收益是自动化：边界内的命令可以免审批放行（codex 沙箱内命令少弹审批、CCB 的 `autoAllowBashIfSandboxed`；oh-my-pi 是反面例证——没有沙箱，要么弹窗疲劳要么默认放行）。
2. **五仓两极分化。** codex、DSH、CCB 三家做了完整的进程沙箱（macOS Seatbelt、Linux Landlock + seccomp + bubblewrap、Windows 受限令牌/ACL）；oh-my-pi 与 opencode 完全没有进程隔离，只有应用层审批，且两家文档都明确承认审批不是遏制。
3. **模式词表已经收敛：** read-only / workspace-write / danger-full-access 三档，且只描述文件系统效果；网络是独立维度（codex 本地默认禁网、开启后走代理允许列表；DSH 直接声明网络不在 SandboxMode 定义范围内）。
4. **工程难点不在「关进去」，而在四件配套：** 违规如何被识别（stderr 方言化）、违规后如何升级重跑（批准 = 携带更宽策略的新调用）、强制执行完整性如何诚实上报（DSH 的 full/partial）、如何防「拆护栏」（CCB 强制 deny 写自身配置文件）。
5. **对 LoopCraft 的定位：** 当前没有任何本地进程执行类工具，沙箱不是现有循环的需求，而是「工具面引入执行类工具」时的前置决策。且 LoopCraft 是服务器上执行租户意图的多租户平台，与本机 harness「用户自己的机器自己负责」的信任前提不同——若引入执行工具，边界应从容器级起步，而不是照搬 syscall 级沙箱，见第 6 节。

## 2. LoopCraft 现状盘点

以下均为当前代码事实：

- **没有进程执行能力。** 全仓检索 `ProcessBuilder` / `Runtime.exec` 零命中。工具面是纯 Java 只读工具：`tool/SkillTools.forTemplate(tenantId, templateId)` 构建本次运行固定的 `Map<String, ToolCallback>`（当前 `list_skills` / `read_skill`）；`ToolExecutor` 做白名单查找与错误观察回填；`ToolResults` envelope 做单项截断（`AgentProperties.maxToolResultChars`）。
- **没有权限/审批概念。** 工具表在受理时固定，运行中无人为介入：SSE 通道只下行事件，run 取消接口还在 README 计划清单里。运行期约束只有 `RunBudget`（maxIterations、runTimeout、modelRequestTimeout、toolTimeout、maxToolCalls）与工具结果截断。
- **MCP 动态工具（共创中）是远程调用。** MCP 工具在远端服务进程内执行，不在 LoopCraft 本机 spawn 子进程——其风险面在远端服务与连接层，不在本机沙箱议题内。
- **部署形态。** 云端多租户（`tenant_id` 贯穿业务表），本地 `./scripts/dev.sh up` 用 podman 起依赖；本机容器镜像拉取受限、依赖缓存镜像是既有运维约束。
- **路线图位置。** 成熟度路线图中排在会话/压缩之后的「工具面 → 权限」层；README 下一步清单最近的相关项是「业务写操作工具（需授权与幂等语义）」。执行类工具尚未列入任何已批准计划。

## 3. 主流机制总览

| 层级 | 机制 | 代表实现 |
|---|---|---|
| 0 | 应用层审批（allow/ask/deny 规则、审批模式） | 五仓全部；oh-my-pi / opencode 仅有此层 |
| 1 | 进程文件系统沙箱（读写白名单） | codex Landlock / Seatbelt / Windows 受限令牌；DSH 三后端同款；CCB 经外部 sandbox-runtime |
| 2 | syscall 过滤（禁网、禁调试） | codex seccomp（见 4.1）；CCB Linux = bubblewrap + seccomp |
| 3 | 网络出口控制 | codex 本地 network-proxy 允许列表（托管 MITM CA）；CCB 域名白名单 + Hook 专用 network-only 沙箱 + 越界授权弹窗 |
| 4 | 命名空间/PID 隔离 | codex bwrap（系统 bwrap 或 bundled helper，需 unprivileged userns；`codex-rs/bwrap` 直接 vendor 源码）；CCB Linux 同源 |
| 5 | 容器/VM | codex 云端「云容器使用它们自己的策略」；DSH 根目录 SAFETY.md 建议一次性 VM/容器 |

词表补充：三档 `SandboxMode` 只描述文件效果，网络与进程可见性是独立维度；升级重跑统一为「一次新的、更宽的调用」，不在运行中放宽既有进程。

## 4. 逐仓设计要点

### 4.1 codex（最完整，可作设计基准）

- **配置面。** `SandboxMode` 三档（`codex-rs/protocol/src/config_types.rs:104`，枚举代码默认 read-only，官方文档描述 CLI 默认 workspace-write）；`AskForApproval` = OnRequest（默认）/ Granular{sandbox_approval, rules, skill_approval, request_permissions, mcp_elicitations} / Never，untrusted 已退役（`codex-rs/protocol/src/protocol.rs:1097`）。execpolicy 规则系统负责「沙箱外命令」的 allow/prompt/forbid 前缀分类，`execpolicy/src/sandbox_migration.rs` 一次性清除被沙箱取代的旧 allow 规则。审批可交给 auto_review 审批器代理（官方文档的 approvals_reviewer）。
- **后端选择。** `sandboxing/src/manager.rs:50` `get_platform_sandbox` → macOS MacosSeatbelt / Linux LinuxSeccomp / Windows WindowsRestrictedToken（需显式启用），另有 WindowsMxc 分支。`PermissionProfile` = Managed{file_system, network} / Disabled / External{network}（`protocol/src/models.rs:425`）是更新的抽象，其中 **External 显式声明「文件系统隔离由外部完成」**——对 LoopCraft 这种外层有容器/隔离设施的形态是直接可借鉴的建模。
- **macOS。** Seatbelt profile deny-default、子进程继承策略、只放行 `/dev/null` 字符设备写、sysctl 只读白名单（`sandboxing/src/seatbelt_base_policy.sbpl`，自述 inspired by Chrome's sandbox policy）；网络、只读平台默认、偏好目录各有独立 sbpl 文件。
- **Linux。** Landlock 做文件效果（`sandboxing/src/landlock.rs`）；seccomp 禁 ptrace、process_vm_readv/writev、io_uring 全家，禁网时再禁 connect/bind/listen/sendto 等，socket 只放行 AF_UNIX 并禁 vsock（`linux-sandbox/src/seccomp.rs`）；PID namespace 用 bwrap，系统缺 bwrap 时回落 bundled helper，依赖 unprivileged user namespaces；WSL1 明确不支持（`SandboxTransformError::Wsl1UnsupportedForBubblewrap`）。
- **网络。** workspace-write 且 network_access 开启时走本地 network-proxy 允许列表（托管 MITM CA 供 TLS 过滤，`network-proxy` crate、`with_managed_mitm_ca_readable_root`）；本地默认禁网、云端默认开启（官方文档）。
- **违规与升级。** `sandboxing/src/denial.rs` 从退出码/stderr 识别「沙箱拒绝」；`violation.rs` 把文件/网络违规结构化记录；工具层 trait 集中在 `core/src/tools/sandboxing.rs`（Sandboxable / ToolRuntime / SandboxAttempt / ApprovalStore / SandboxOverride / NetworkApprovalSpec）。被拒命令以失败反馈给模型，由审批流决定是否以更高权限重跑。
- **自保护。** `sandboxing/src/sandbox_integrity`（FileContentsChecker）校验沙箱二进制未被篡改；另有 `process-hardening` crate。

### 4.2 claude-code / CCB（适配层 + 外部 runtime，文档最完整）

- **分层。** 仓库自身只做策略、配置转换、启停判断、命令包裹与权限联动；OS 级隔离全部在外部运行时 `@anthropic-ai/sandbox-runtime`（`src/utils/sandbox/sandbox-adapter.ts` 导入 BaseSandboxManager / SandboxViolationStore 等）。`docs/safety/sandbox.mdx` 是五仓中唯一的沙箱专文，以下要点均出自它。
- **平台。** macOS sandbox-exec（Seatbelt profile）；Linux/WSL2 bubblewrap + seccomp；WSL1 不支持；Windows 原生不支持，只剩权限系统。
- **配置由权限系统「翻译」而来（核心设计）。** `Edit(...)` / `Read(...)` / `WebFetch(domain:...)` 权限规则被翻译成沙箱的文件/网络限制，再叠加 `sandbox.filesystem.allow*/deny*`；默认可写仅当前工作目录 + Claude 临时目录；强制 deny 写 settings.json、`.claude/skills`、bare git 路径——防「命令顺手拆掉护栏」。
- **审批换自动化。** `autoAllowBashIfSandboxed` 让确定会进沙箱的命令免弹窗（显式 deny/ask 仍优先）；`excludedCommands` 精确/前缀/通配排除；`dangerouslyDisableSandbox` + `allowUnsandboxedCommands` 是刻意显眼的例外通道；`failIfUnavailable` 时沙箱不可用直接拒绝启动，不静默降级。
- **违规体验三分类。** 执行前权限确认；运行中违规（stderr 附加 `<sandbox_violations>` 标签给模型、UI 剥离后展示、ViolationStore 记录）；网络越界（专门授权对话框，按 host 临时放行）。文件越界刻意不做「运行中再放行一次」，保持边界稳定。
- **范围边界。** BashTool/PowerShell 走沙箱，Hook 命令走 network-only 沙箱；FileEdit/FileWrite 等直接文件工具不走沙箱，由应用层权限把关；文档反复强调「Bash AST 分析不是沙箱」。

### 4.3 deepseek-harness（seam 化最彻底，工程语义最严谨）

- **架构。** `packages/sandbox/sandbox` 定义抽象 seam（`ctx.sandbox.confine(argv, policy)` 返回包装后的 argv），`sandbox-local` 提供 Linux bwrap/Landlock、macOS Seatbelt、Windows ACL 受限令牌三后端，消费方是 `bash-sandbox` / `pwsh-sandbox`，`sandbox-ssh` 把同一策略执行在远端（`docs/subsystems/sandbox.zh.md`）。
- **逐调用策略。** 策略不固定在 provider 上，每次能力调用解析一次 `{mode, workspaceRoot, sessionId}`；优先级 = 已批准的显式模式 > 会话最近 sandbox/mode 事件 > 部署默认。**已批准的升级重试 = 同一 root、更宽模式的新调用**，因此并发会话可同时对同一 provider 请求不同边界（bash 只读、受限子 agent 可写状态目录）。
- **诚实的强制执行上报。** `SandboxEnforcement = full | partial`：旧 Landlock ABI、Windows ACL 的硬链接/读取缺口如实标 partial，「要求绝对保证的消费方必须拒绝或向上暴露」。
- **违规方言。** 每个后端声明自己的 denial stderr 签名（EROFS@bwrap、EACCES@Landlock、EPERM@Seatbelt），消费方只匹配所选后端的方言而非跨后端并集；`RunnerFailureRules` 先区分「沙箱 runner 自身失败（基础设施故障）」与「命令被沙箱拒绝（隔离生效）」，退出码不能单独作为证据。
- **fail-closed。** 无可用后端直接 `SANDBOX_UNAVAILABLE`，「静默的无隔离透传永远不合法」。根目录 `SAFETY.md` 同时声明项目未经审计、不应作为不可信工作负载的唯一安全控制，建议一次性 VM/容器。

### 4.4 oh-my-pi / opencode（无进程沙箱的对照组）

- **oh-my-pi**（`docs/approval-mode.md`）：审批三档 tier read/write/exec，未知工具默认 exec（安全默认），MCP 工具一律按 write；模式 always-ask / write / yolo（**默认 yolo，即全放行**）；bash 对关键危险模式（rm -rf /、fork 炸弹、远程拉取即执行、写 /etc/passwd、关机）强制 prompt，`bash.patterns` 支持 deny/prompt/allow。文档自述：「审批策略控制的是要不要执行，不是进程或文件系统遏制；被批准的命令保留 shell 原生的文件系统、网络与子进程访问」。执行时无设置且无 autoApprove 则 fail-closed 到 always-ask。
- **opencode**（`packages/core/src/permission.ts`）：通用权限框架（action/resource/effect，ask once/always/deny，deny 一票否决），全仓无 seatbelt/bubblewrap/landlock/seccomp 命中；`project.sandboxes`（`packages/schema/src/project.ts:48`）与 workspace driver 的 attach/suspend 指向外部托管工作区环境，不是本机命令隔离；`packages/containers` 是应用自身打包，与 agent 沙箱无关。

## 5. 跨仓共识

1. 沙箱与审批正交且互相成就：沙箱收窄边界 → 边界内免审批 → 自动化程度上升；没有沙箱就只能在弹窗疲劳与裸奔之间选。
2. 三档模式词表 + 网络独立维度；`danger-full-access` 不是「一种沙箱」，而是「不调用沙箱」。
3. 升级重跑语义统一：批准产生一次新的、更宽的执行，不在运行中放宽既有进程。
4. 违规识别靠后端方言化的 stderr/退出码，且要与「沙箱基础设施自身失败」区分；违规信息要同时反馈给模型（标签化）与用户（事件/记录）。
5. 平台与内核缺口诚实处理：Windows 原生不支持或受限令牌打折（如实标 partial）；沙箱不可用时 fail-closed，不静默降级。
6. 防拆护栏：沙箱与审批器自身的配置对被隔离进程不可写。

## 6. LoopCraft 适用性：云端与本机的本质差异

参考项目全部是**本机单用户** harness：资产边界就是用户自己的机器与文件，沙箱防的是「模型意图越过用户预期」。LoopCraft 是**服务器上执行租户意图的多租户平台**：要防的是「租户的运行伤害平台与其他租户」，信任前提完全不同。由此：

1. **审批层不能照搬。** 本机 harness 的 ask 是弹窗问坐在屏幕前的用户；LoopCraft 运行中没有任何人工通道（SSE 仅下行）。可对应的形态是**受理前的静态授权**（模板/租户级配置：允许哪些工具、什么边界）+ **运行中违规即失败并作为观察反馈模型**（CCB 的 `<sandbox_violations>` 思路；LoopCraft 现有 `tool_called` / `tool_finished` 事件不含工具参数与结果内容，天然满足脱敏纪律）。运行中人工审批需等会话与插话能力落地。
2. **隔离层级应从容器级起步。** syscall 级沙箱防文件与网络越界，不防资源耗尽与内核漏洞；参考项目在服务器侧（codex 云端、DSH 官方建议）也都落到容器/VM。Java 没有这些内核机制的原生绑定，自研 helper 的收益低于直接用容器隔离（podman 无 root 守护、本机已有使用经验；镜像拉取受限是需先解决的运维前提）。microVM/gVisor 属远期。
3. **「本机执行工具」与「远程工具」风险面不同。** MCP 工具在远端执行，不进本机沙箱；只有 LoopCraft 自己 spawn 子进程的工具（shell、代码执行、文件处理）才进沙箱议题。codex 的 `PermissionProfile::External` 正是「外层容器 + 内层声明」的建模先例。
4. **工具面协议可以先于执行器定型。** 三档模式 + 网络开关 + 违规观察的结构不影响 `agent.core`：工具仍是 ToolCallback，违规仍是失败的工具结果。可先以假执行器桩驱动循环测试，与现有「假模型测试」纪律一致。

## 7. 决策点清单

1. **执行类工具是否进入近期范围？** README 下一步的「业务写操作工具」目前指业务 API 写操作；shell/代码执行是另一档风险，需单独授权。本文仅存档备查。
2. **隔离层级：** 容器级（建议：每 run 或每租户容器，非 root、只读根、无网或 egress 白名单、CPU/内存/时长限额）vs 自研 syscall 级 helper vs 仅审批（云端多租户不可接受）。
3. **模式词表：** 是否采纳 read-only / workspace-write / danger-full-access 三档文件效果 + 独立网络开关；danger 档是否对租户开放、由谁审批。
4. **授权形态：** 受理前静态授权落在模板级还是租户级；`lc_agent_template` 是否需要新增执行边界字段（涉及改列须同步 `docs/database-schema.md`）。
5. **违规反馈：** 违规以标签化工具结果反馈模型、以事件外显（脱敏，不含原始输出）；违规是否计入运行审计。
6. **资源约束：** 容器 CPU/内存/时长与现有 `RunBudget`（toolTimeout 已有）的关系；输出体量沿用 `ToolResults` 截断。
7. **运维前提：** 执行环境镜像的构建与分发（受本机镜像拉取限制约束）；每 run 容器冷启动成本与池化。

## 8. 建议路径（待讨论）

1. **若授权执行类工具：** 协议先行——工具面定义执行边界参数与违规观察结构，执行器作为 `tool/` 下独立模块先以假桩实现（固定输出/固定违规），循环行为用假模型测试；容器执行器随后接入，测试同样以桩替换。全程不触碰 `agent.core`。
2. **第二阶段：** egress 白名单、违规事件外显与运行审计、每 run 资源限额、镜像池化。
3. **远期：** 会话与插话落地后的运行中审批；执行环境独立成服务（与多实例水平扩展方向一致）。

按仓库约定，核心循环的实现由用户亲自完成；本文仅收敛隔离设计、配置方向与决策清单。

## 参考来源

- OpenAI Codex 官方沙箱文档：<https://developers.openai.com/codex/sandboxing>
- 本地参考快照（均为只读静态阅读）：codex `codex-rs/sandboxing/`、`codex-rs/linux-sandbox/`、`codex-rs/protocol/src/{config_types,models,protocol}.rs`、`codex-rs/core/src/tools/sandboxing.rs`；CCB `docs/safety/sandbox.mdx`、`src/utils/sandbox/sandbox-adapter.ts`；DSH `docs/subsystems/sandbox.zh.md`、`SAFETY.md`、`packages/sandbox/`；oh-my-pi `docs/approval-mode.md`；opencode `packages/core/src/permission.ts`、`packages/schema/src/project.ts`
