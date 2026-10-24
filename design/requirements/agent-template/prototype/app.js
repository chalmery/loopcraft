/* agent-template r3：独立配置原型，数据仅保存在页面内存。 */
const $ = (selector) => document.querySelector(selector);
const html = (value) => String(value ?? "").replace(/[&<>"']/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
const clone = (value) => structuredClone(value);
const tabs = [["basic", "基本信息"], ["tools", "工具"], ["context", "上下文"], ["runtime", "运行设置"]];
const models = [
  { id: 1, name: "示例·均衡模型", source: "团队模型连接", enabled: true, contextLimit: 131072 },
  { id: 2, name: "示例·推理模型", source: "团队模型连接", enabled: true, contextLimit: 262144 },
  { id: 3, name: "示例·轻量模型", source: "备用模型连接", enabled: true, contextLimit: null },
  { id: 4, name: "示例·归档模型", source: "已停用的连接", enabled: false, contextLimit: 32768 },
];
const skills = [
  { id: 1, name: "资料整理", description: "提取核心信息，整理结构并标明来源。", latest: 3, enabled: true },
  { id: 2, name: "技术写作", description: "面向读者组织技术说明与实践步骤。", latest: 2, enabled: true },
  { id: 3, name: "代码审查", description: "结合代码上下文，检查正确性与边界。", latest: 4, enabled: true },
  { id: 4, name: "任务拆解", description: "明确目标，形成可执行的步骤与验收点。", latest: 1, enabled: true },
  { id: 5, name: "旧版检索方法", description: "已停用，保留已有配置供维护。", latest: 2, enabled: false },
];
const defaults = { contextBudgetTokens: null, summaryModelId: null, keepRecentMessages: 10, maxIterations: 0, timeoutMinutes: 0, temperature: null };
let templates = [
  { ...defaults, id: 101, name: "通用助手", description: "日常问答、信息整理与任务拆解。", enabled: true, modelId: 1, prompt: "你是一位清晰、可靠的工作助手。\n\n先理解用户的目标，再提供直接、可执行的回答。\n信息不足时说明缺口；无法确认的事实，不作猜测。\n\n回答默认使用中文，尽量简洁。", skills: [{ id: 4, version: 1 }], revision: 1, updated: "10 月 24 日" },
  { ...defaults, id: 102, name: "研究与写作", description: "梳理资料、建立论据，把复杂问题写清楚。", enabled: true, modelId: 2, prompt: "你是一位研究与写作助手。\n\n先明确问题与受众，再组织已有材料。\n区分事实、推断与建议，保留有依据的来源。", skills: [{ id: 1, version: 3 }, { id: 2, version: 2 }], contextBudgetTokens: 65536, maxIterations: 16, timeoutMinutes: 2.5, revision: 2, updated: "10 月 23 日" },
  { ...defaults, id: 103, name: "代码审查助手", description: "聚焦可复现的问题、边界条件与修改建议。", enabled: false, modelId: 1, prompt: "你是一位代码审查助手。\n\n先阅读上下文，再判断问题的触发条件与影响。\n每条建议应对应具体代码与可观察的行为。", skills: [{ id: 3, version: 4 }], revision: 1, updated: "10 月 22 日" },
];
let page = "list";
let draft = null;
let baseline = "";
let activeTab = "basic";
let filter = "all";
let query = "";
let skillQuery = "";
let readOnly = false;
let busy = false;
let lastScenario = "normal";
let pendingConfirm = null;
let returnFocus = null;
let toastTimer;
let validationShown = false;
const dirty = () => draft !== null && JSON.stringify(draft) !== baseline;
const blocked = () => readOnly || busy;
const scenario = () => $("#scenario").value;
const modelById = (id) => models.find((item) => item.id === id);
const skillById = (id) => skills.find((item) => item.id === id);
const unavailableModel = (id) => !modelById(id)?.enabled || (scenario() === "unavailable" && id === 1);
const unavailableSkill = (id) => !skillById(id)?.enabled || (scenario() === "unavailable" && id === 4);

function issues(item) {
  const result = [];
  if (item.modelId && unavailableModel(item.modelId)) result.push("运行模型或连接已停用");
  if (item.summaryModelId && unavailableModel(item.summaryModelId)) result.push("摘要模型或连接已停用");
  if (item.skills.some((binding) => unavailableSkill(binding.id))) result.push("部分 Skill 已停用");
  if (item.modelId && !modelById(item.modelId)?.contextLimit) result.push("模型尚未配置上下文上限");
  if (item.summaryModelId && !modelById(item.summaryModelId)?.contextLimit) result.push("摘要模型尚未配置上下文上限");
  return result;
}
function toast(message) {
  clearTimeout(toastTimer);
  $("#toast").textContent = message;
  $("#toast").hidden = false;
  toastTimer = setTimeout(() => { $("#toast").hidden = true; }, 5500);
}
function confirmAction(title, description, actionLabel, callback, dangerous = false) {
  returnFocus = document.activeElement;
  $("#confirm-title").textContent = title;
  $("#confirm-description").textContent = description;
  $("#confirm-accept").textContent = actionLabel;
  $("#confirm-accept").className = dangerous ? "danger" : "";
  $("#confirm-cancel").textContent = dangerous ? "取消" : "继续编辑";
  pendingConfirm = callback;
  $("#confirm-dialog").showModal();
  $("#confirm-cancel").focus();
}
function guarded(callback) {
  if (busy) { toast("正在保存，请稍后再操作。"); return; }
  if (!dirty()) { callback(); return; }
  confirmAction("放弃尚未保存的修改？", "离开后，本次尚未保存的内容将丢失。", "放弃修改", callback);
}
function listPage() { page = "list"; draft = null; baseline = ""; render(); }
function openTemplate(id, copy = false, initialTab = "basic") {
  draft = clone(templates.find((item) => item.id === id) ?? { ...defaults, id: null, name: "", description: "", enabled: true, modelId: null, prompt: "", skills: [], revision: 0, updated: "" });
  if (copy) {
    draft.id = null; draft.revision = 0;
    const sourceName = draft.name;
    let number = 1;
    do {
      const suffix = number === 1 ? "（副本）" : `（副本 ${number}）`;
      draft.name = sourceName.slice(0, 80 - suffix.length) + suffix;
      number += 1;
    } while (templates.some((item) => item.name === draft.name));
  }
  baseline = JSON.stringify(draft);
  page = "editor";
  activeTab = tabs.some(([key]) => key === initialTab) ? initialTab : "basic";
  skillQuery = "";
  validationShown = false;
  render();
  window.scrollTo({ top: 0 });
  if (!readOnly && !id) $("#template-name").focus();
}
function render() {
  $("#readonly").disabled = busy;
  $("#scenario").disabled = busy;
  $("#fail-save").disabled = busy;
  $("#app").innerHTML = page === "list" ? renderList() : renderEditor();
  if (page === "list") renderCards();
  else { renderSkills(); updateDerived(); updateDirty(); if (validationShown) showValidation(); }
}
function renderList() {
  const count = scenario() === "empty" ? 0 : templates.length;
  const enabled = count ? templates.filter((item) => item.enabled).length : 0;
  return `<div class="page-header"><div><div class="page-title"><h1>模板配置</h1><span class="badge">${count}</span></div><p>把模型、提示词和 Skill 组合成可复用的 Agent。</p></div>${readOnly ? "" : '<button data-action="new">＋ 新建模板</button>'}</div>
    ${readOnly ? '<div class="alert">当前为只读预览。正式模板由租户成员共同维护。</div>' : ""}
    <div class="filterbar"><div class="tabs" aria-label="模板状态">${[["all", "全部", count], ["enabled", "已启用", enabled], ["disabled", "已停用", count - enabled]].map(([value, label, total]) => `<button class="tab" data-filter="${value}" aria-pressed="${filter === value}">${label}<span>${total}</span></button>`).join("")}</div><label class="search"><span aria-hidden="true">⌕</span><input id="template-search" aria-label="搜索模板" value="${html(query)}" placeholder="搜索名称或描述" maxlength="160" /></label></div><div class="results" id="results" aria-live="polite"></div><div id="cards"></div>`;
}
function renderCards() {
  const matching = scenario() === "empty" ? [] : templates.filter((item) => `${item.name} ${item.description}`.toLowerCase().includes(query.trim().toLowerCase()));
  const records = matching.filter((item) => filter === "all" || item.enabled === (filter === "enabled"));
  for (const key of ["all", "enabled", "disabled"]) {
    $(`[data-filter="${key}"] span`).textContent = matching.filter((item) => key === "all" || item.enabled === (key === "enabled")).length;
  }
  $("#results").textContent = scenario() === "loading" ? "正在加载模板…" : scenario() === "error" ? "暂时无法加载" : `共 ${scenario() === "empty" ? 0 : records.length} 个模板`;
  if (scenario() === "loading") { $("#cards").innerHTML = `<div class="cards" aria-busy="true">${[1, 2, 3].map(() => '<div class="skeleton"><div></div><div></div><div></div></div>').join("")}</div>`; return; }
  if (scenario() === "error") { $("#cards").innerHTML = '<div class="empty"><h2>模板加载失败</h2><p>暂时无法获取模板，请重试。</p><button class="secondary" data-action="retry">重新加载</button></div>'; return; }
  if (scenario() === "empty" || !records.length) {
    const filtered = !!query.trim() || filter !== "all";
    $("#cards").innerHTML = `<div class="empty"><h2>${filtered ? "没有找到匹配的模板" : "创建你的第一个 Agent 模板"}</h2><p>${filtered ? "试试其他关键词，或清空筛选条件。" : "选择运行模型，按需要编写提示词和添加 Skill。"}</p>${filtered ? '<button class="secondary" data-action="clear-filter">清空筛选</button>' : readOnly ? "" : '<button data-action="new">新建模板</button>'}</div>`;
    return;
  }
  $("#cards").innerHTML = `<div class="cards">${records.map((item) => `<button class="template-card" data-open="${item.id}" aria-label="${readOnly ? "查看" : "编辑"} ${html(item.name)}"><span class="card-title"><span class="card-name">${html(item.name)}</span><span class="badge">${item.enabled ? "已启用" : "已停用"}</span></span><span class="card-description">${html(item.description || "暂无描述")}</span><span class="card-model">${html(modelById(item.modelId)?.name ?? "未选择模型")}${issues(item).length ? '<span class="badge warning">配置不可用</span>' : ""}</span><span class="card-footer"><span>${item.skills.length} 个 Skill</span><span>${html(item.updated)}更新</span></span></button>`).join("")}${readOnly ? "" : '<button class="template-card new-card" data-action="new"><span aria-hidden="true">＋</span><span>新建模板</span></button>'}</div>`;
}
function modelOptions(selectedId, sameAsMain = false) {
  return `<option value="">${sameAsMain ? "与运行模型相同" : "选择运行模型"}</option>` + [...new Set(models.map((item) => item.source))].map((source) => `<optgroup label="${html(source)}">${models.filter((item) => item.source === source).map((item) => `<option value="${item.id}" ${selectedId === item.id ? "selected" : ""} ${unavailableModel(item.id) ? "disabled" : ""}>${html(item.name)}${unavailableModel(item.id) ? "（已停用）" : ""}</option>`).join("")}</optgroup>`).join("");
}
function field(label, input, hint = "", suffix = "") {
  return `<label class="field"><span class="field-title">${label}${suffix ? `<small>${suffix}</small>` : ""}</span>${input}${hint ? `<span class="hint">${hint}</span>` : ""}</label>`;
}
function basicPanel(disabled) {
  return `<section class="panel settings-panel"><div class="section-heading"><div><h2>基本信息</h2><p>定义这份 Agent 的名称、模型和工作方式。</p></div></div>
    <div class="field-grid">${field("模板名称", `<input id="template-name" data-field="name" maxlength="80" value="${html(draft.name)}" placeholder="例如：研究与写作" required ${disabled} />`, "", "必填")}${field("描述", `<input data-field="description" maxlength="300" value="${html(draft.description)}" placeholder="这份模板适合处理什么任务" ${disabled} />`, "", "选填")}</div>
    <div class="field-grid">${field("运行模型", `<select id="model-id" data-field="modelId" ${disabled}>${modelOptions(draft.modelId)}</select>`, '<span id="model-source"></span>', "必填")}${field("温度", `<input type="number" id="temperature" data-field="temperature" min="0" max="2" step="any" value="${html(draft.temperature)}" placeholder="使用模型默认值" ${disabled} />`, "留空时不发送温度参数。", "选填")}</div>
    <div class="prompt-label"><label for="system-prompt">系统提示词</label><span class="hint">选填</span></div><textarea id="system-prompt" class="prompt-area" data-field="prompt" maxlength="20000" placeholder="说明 Agent 的角色、工作方法与回答要求……" spellcheck="false" ${disabled}>${html(draft.prompt)}</textarea><div class="editor-footnote"><span>纯文本，不解析变量。</span><span id="prompt-count">${draft.prompt.length.toLocaleString()} / 20,000</span></div>
    <div class="status-row"><label><input type="checkbox" data-field="enabled" ${draft.enabled ? "checked" : ""} ${disabled} />启用模板</label><span class="hint">停用后仍可编辑，不可用于新任务</span></div></section>`;
}
function toolsPanel() {
  return `<div class="tools-layout"><section class="panel"><div class="section-heading"><div><h2>Skill</h2><p>选择当前租户的 Skill，添加到模板。</p></div><span class="badge">${skills.length}</span></div><label class="search full-width"><span aria-hidden="true">⌕</span><input id="skill-search" aria-label="搜索 Skill" value="${html(skillQuery)}" placeholder="搜索名称或描述" /></label><div id="skill-catalog"></div></section>
    <section class="panel"><div class="section-heading"><div><h2>已选 Skill <span class="muted" id="skill-count">${draft.skills.length}</span></h2><p>固定内容版本，需要时手动切换更新。</p></div></div><div id="selected-skills"></div></section></div>`;
}
function contextPanel(disabled) {
  return `<section class="panel settings-panel"><div class="section-heading"><div><h2>上下文预算</h2><p>包含完整输入和输出预留，留空时使用模型最大上下文。</p></div></div>
    <div class="compact-fields">${field("上下文预算", `<div class="input-unit"><input type="number" id="context-budget" data-field="contextBudgetTokens" min="1" step="1" value="${html(draft.contextBudgetTokens)}" placeholder="跟随模型上限" ${disabled} /><span>Token</span></div>`, '<span id="context-capacity"></span>', "选填")}</div><p class="hint" id="effective-budget"></p>
    <div class="divider"></div><div class="section-heading"><div><h2>历史摘要</h2><p>保留最近的原始消息，将较早的历史整理为摘要。</p></div><span class="badge">固定方式</span></div>
    <div class="field-grid">${field("摘要模型", `<select id="summary-model" data-field="summaryModelId" ${disabled}>${modelOptions(draft.summaryModelId, true)}</select>`, '<span id="summary-current"></span>')}${field("保留最近消息数", `<div class="input-unit"><input type="number" id="keep-messages" data-field="keepRecentMessages" min="1" step="1" value="${draft.keepRecentMessages}" required ${disabled} /><span>条</span></div>`, "默认 10 条，工具调用与结果整组保留。")}</div>
    <p class="hint">按消息条数计算，不是对话轮数；整组保留时可能略多于设定值。系统提示词独立保留，不占条数。</p></section>`;
}
function runtimePanel(disabled) {
  return `<section class="panel settings-panel"><div class="section-heading"><div><h2>运行设置</h2><p>配置一次任务的循环次数和运行时间。</p></div></div><div class="field-grid">${field("最大循环次数", `<div class="input-unit"><input type="number" id="max-iterations" data-field="maxIterations" min="0" step="1" required value="${draft.maxIterations}" ${disabled} /><span>次</span></div>`, "0 表示不限次数，仅统计主模型调用。")}${field("运行超时", `<div class="input-unit"><input type="number" id="timeout-minutes" data-field="timeoutMinutes" min="0" step="any" required value="${draft.timeoutMinutes}" ${disabled} /><span>分钟</span></div>`, "0 表示不限时；支持小数，如 0.5 为 30 秒。")}</div></section>`;
}
function renderEditor() {
  const disabled = blocked() ? "disabled" : "";
  const existing = draft.id !== null;
  const panels = { basic: basicPanel(disabled), tools: toolsPanel(), context: contextPanel(disabled), runtime: runtimePanel(disabled) };
  return `<div class="breadcrumb"><button class="ghost" data-action="back">← 模板配置</button><span>/</span><span>${existing ? readOnly ? "查看模板" : "编辑模板" : "新建模板"}</span></div>
    <div class="editor-toolbar"><div class="page-header"><div><div class="page-title"><h1>${existing ? html(draft.name) : "新建模板"}</h1><span class="badge" id="dirty-badge" hidden>未保存</span>${readOnly ? '<span class="badge">只读</span>' : ""}</div><p>当前租户共享 · 所有成员可维护 · 四个栏目统一保存</p></div><div class="actions"><button class="secondary" data-action="back" ${busy ? "disabled" : ""}>${readOnly ? "返回列表" : "取消"}</button>${!readOnly && existing ? `<button class="secondary" data-action="copy" title="复制已保存的配置" ${disabled}>复制模板</button>` : ""}${readOnly ? "" : `<button id="save-template" data-action="save" ${disabled}>${busy ? "保存中…" : "保存模板"}</button>`}</div></div>
    <div class="config-tabs" role="tablist" aria-label="模板配置栏目">${tabs.map(([key, label]) => `<button id="tab-${key}" class="tab" role="tab" aria-selected="${activeTab === key}" aria-controls="panel-${key}" tabindex="${activeTab === key ? 0 : -1}" data-config-tab="${key}">${label}${key === "tools" ? `<span id="tools-tab-count">${draft.skills.length}</span>` : ""}<span class="tab-error" id="errors-${key}" hidden></span></button>`).join("")}</div></div>
    <div class="alert warning editor-notice" id="availability-alert" hidden></div>
    <div class="alert warning editor-notice" id="model-impact" hidden></div>
    <div class="alert error-summary editor-notice" id="validation-summary" role="status" hidden></div>
    <form id="template-form" novalidate>${tabs.map(([key]) => `<div class="tab-content" id="panel-${key}" role="tabpanel" aria-labelledby="tab-${key}" ${activeTab === key ? "" : "hidden"}>${panels[key]}</div>`).join("")}</form>
    <div class="footer-note"><span>${existing ? `最近更新：${html(draft.updated)}` : "首次保存后出现在模板列表"}</span>${existing && !readOnly ? `<button class="ghost danger-text" data-action="delete" ${disabled}>删除模板</button>` : ""}</div>`;
}
function switchTab(key, focus = false) {
  if (!tabs.some(([value]) => value === key)) return;
  activeTab = key;
  for (const [value] of tabs) {
    $(`#panel-${value}`).hidden = key !== value;
    $(`#tab-${value}`).setAttribute("aria-selected", String(key === value));
    $(`#tab-${value}`).tabIndex = key === value ? 0 : -1;
  }
  if (focus) $(`#tab-${key}`).focus();
}
function renderSkills() {
  const matching = skills.filter((item) => `${item.name} ${item.description}`.toLowerCase().includes(skillQuery.trim().toLowerCase()));
  $("#skill-catalog").innerHTML = matching.map((item) => {
    const selected = draft.skills.some((binding) => binding.id === item.id);
    return `<label class="catalog-row ${selected ? "selected" : ""} ${unavailableSkill(item.id) && !selected ? "unavailable" : ""}"><input type="checkbox" data-bind-skill="${item.id}" ${selected ? "checked" : ""} ${blocked() || (unavailableSkill(item.id) && !selected) ? "disabled" : ""} /><span class="catalog-content"><span class="catalog-name">${html(item.name)} <small>${unavailableSkill(item.id) ? "已停用" : `v${item.latest}`}</small></span><span class="catalog-description">${html(item.description)}</span></span></label>`;
  }).join("") || '<div class="skill-placeholder">没有匹配的 Skill。</div>';
  $("#skill-count").textContent = draft.skills.length;
  $("#tools-tab-count").textContent = draft.skills.length;
  $("#selected-skills").innerHTML = draft.skills.length ? draft.skills.map((binding) => {
    const skill = skillById(binding.id);
    return `<div class="skill-row"><div class="skill-info"><strong>${html(skill.name)}</strong>${unavailableSkill(skill.id) ? ' <span class="badge warning">已停用</span>' : ""}<p>${html(skill.description)}</p>${binding.version < skill.latest ? `<p class="version-note">有新版本 v${skill.latest}，当前固定 v${binding.version}</p>` : ""}</div><div class="row-actions"><select aria-label="${html(skill.name)}版本" data-skill-version="${skill.id}" ${blocked() || unavailableSkill(skill.id) ? "disabled" : ""}>${Array.from({ length: skill.latest }, (_, index) => skill.latest - index).map((version) => `<option value="${version}" ${version === binding.version ? "selected" : ""}>v${version}${version === skill.latest ? " · 最新" : ""}</option>`).join("")}</select>${readOnly ? "" : `<button type="button" class="ghost icon-button" data-remove-skill="${skill.id}" aria-label="移除 ${html(skill.name)}" ${busy ? "disabled" : ""}>×</button>`}</div></div>`;
  }).join("") : '<div class="skill-placeholder selected-empty"><strong>还没有添加 Skill</strong><p>选择需要的 Skill，也可以保持为空。</p></div>';
}
function updateDerived() {
  const model = modelById(draft.modelId);
  $("#model-source").textContent = model ? `来自 ${model.source}` : "按连接分组，使用当前租户的模型。";
  $("#context-capacity").textContent = model?.contextLimit ? `当前模型上限：${model.contextLimit.toLocaleString()} Token，在模型接入维护。` : model ? "当前模型尚未设置上下文上限，可保存配置，使用前需补齐。" : "先在基本信息中选择运行模型。";
  $("#effective-budget").textContent = draft.contextBudgetTokens === null ? model?.contextLimit ? `当前预算：${model.contextLimit.toLocaleString()} Token（跟随模型）。输出空间由系统预留。` : "补齐模型上限后自动采用该上限。" : `自定义预算：${html(draft.contextBudgetTokens)} Token；清空可恢复跟随模型。`;
  const exceeds = model?.contextLimit && draft.contextBudgetTokens > model.contextLimit;
  $("#model-impact").hidden = !exceeds;
  $("#model-impact").innerHTML = exceeds ? `<span>自定义预算超过当前模型的 ${model.contextLimit.toLocaleString()} Token 上限，请调整后保存。</span><button class="ghost" type="button" data-config-tab="context">调整上下文预算 →</button>` : "";
  $("#summary-current").textContent = draft.summaryModelId ? `来自 ${modelById(draft.summaryModelId)?.source}` : model ? `当前使用：${model.name}` : "跟随基本信息中的运行模型。";
  const reasons = issues(draft);
  $("#availability-alert").hidden = reasons.length === 0;
  $("#availability-alert").textContent = `${reasons.join("；")}。可以继续维护和保存，当前配置不可用于运行。`;
}
function updateDirty() {
  $("#dirty-badge").hidden = !dirty();
  $("#save-template")?.toggleAttribute("disabled", blocked() || (draft.id !== null && !dirty()));
}
function showValidation() {
  const errors = [];
  const invalid = (tab, selector, message) => errors.push({ tab, selector, message });
  if (!draft.name.trim()) invalid("basic", "#template-name", "请填写模板名称。");
  else if (templates.some((item) => item.id !== draft.id && item.name === draft.name.trim())) invalid("basic", "#template-name", "当前租户已存在同名模板，请修改名称。");
  if (!modelById(draft.modelId)) invalid("basic", "#model-id", "请选择运行模型。");
  if (draft.temperature !== null && (!Number.isFinite(draft.temperature) || draft.temperature < 0 || draft.temperature > 2)) invalid("basic", "#temperature", "示例模型温度范围为 0–2，也可以留空。");
  if (draft.contextBudgetTokens !== null && (!Number.isSafeInteger(draft.contextBudgetTokens) || draft.contextBudgetTokens < 1)) invalid("context", "#context-budget", "上下文预算应为正整数，也可以留空跟随模型上限。");
  else if (modelById(draft.modelId)?.contextLimit && draft.contextBudgetTokens > modelById(draft.modelId).contextLimit) invalid("context", "#context-budget", `不能超过当前模型的 ${modelById(draft.modelId).contextLimit.toLocaleString()} Token 上限。`);
  if (draft.summaryModelId !== null && !modelById(draft.summaryModelId)) invalid("context", "#summary-model", "请选择有效的摘要模型，或跟随运行模型。");
  if (!Number.isSafeInteger(draft.keepRecentMessages) || draft.keepRecentMessages < 1) invalid("context", "#keep-messages", "保留最近消息数应为正整数。");
  if (!Number.isSafeInteger(draft.maxIterations) || draft.maxIterations < 0) invalid("runtime", "#max-iterations", "请输入非负整数，0 表示不限次数。");
  if (typeof draft.timeoutMinutes !== "number" || !Number.isFinite(draft.timeoutMinutes) || draft.timeoutMinutes < 0) invalid("runtime", "#timeout-minutes", "请输入非负数字，可填小数，0 表示不限时。");
  $("#template-form").querySelectorAll('[aria-invalid="true"]').forEach((element) => { element.removeAttribute("aria-invalid"); element.removeAttribute("aria-describedby"); });
  $("#template-form").querySelectorAll(".field-error").forEach((element) => element.remove());
  for (const error of errors) {
    const input = $(error.selector);
    const message = document.createElement("span");
    message.className = "field-error";
    message.id = `${input.id}-error`;
    message.textContent = error.message;
    input.closest(".field").append(message);
    input.setAttribute("aria-invalid", "true");
    input.setAttribute("aria-describedby", message.id);
  }
  for (const [key] of tabs) {
    const count = errors.filter((item) => item.tab === key).length;
    $(`#errors-${key}`).hidden = count === 0;
    $(`#errors-${key}`).textContent = `${count} 项待调整`;
  }
  $("#validation-summary").hidden = errors.length === 0;
  $("#validation-summary").textContent = `有 ${errors.length} 项配置需要调整，已在对应栏目和字段下方标出。`;
  return errors;
}
function validate() {
  validationShown = true;
  const errors = showValidation();
  if (errors.length) { switchTab(errors[0].tab); $(errors[0].selector).focus(); return false; }
  return true;
}
async function save() {
  if (blocked() || (draft.id !== null && !dirty()) || !validate()) return;
  const fail = $("#fail-save").checked;
  busy = true;
  render();
  await new Promise((resolve) => setTimeout(resolve, 500));
  busy = false;
  if (fail) { render(); toast("保存失败，输入已保留。关闭评审工具中的“保存失败”后可重试。"); $("#save-template").focus(); return; }
  const item = clone(draft);
  item.name = item.name.trim();
  item.description = item.description.trim();
  item.id ??= Math.max(...templates.map((entry) => entry.id), 100) + 1;
  item.revision += 1;
  item.updated = "刚刚";
  templates = templates.filter((entry) => entry.id !== item.id);
  templates.unshift(item);
  draft = clone(item);
  baseline = JSON.stringify(draft);
  validationShown = false;
  if (scenario() === "empty") { lastScenario = "normal"; $("#scenario").value = "normal"; }
  render();
  toast("模板已保存（原型内存）。");
}
$("#app").addEventListener("click", (event) => {
  const button = event.target.closest("button");
  if (!button || button.disabled) return;
  if (button.dataset.open) { openTemplate(Number(button.dataset.open)); return; }
  if (button.dataset.configTab) { switchTab(button.dataset.configTab); return; }
  if (button.dataset.filter) { filter = button.dataset.filter; render(); return; }
  if (button.dataset.removeSkill) {
    const id = Number(button.dataset.removeSkill);
    draft.skills = draft.skills.filter((item) => item.id !== id);
    renderSkills(); updateDerived(); updateDirty();
    const checkbox = $(`[data-bind-skill="${id}"]`);
    if (checkbox && !checkbox.disabled) checkbox.focus();
    else $("#skill-search").focus();
    return;
  }
  switch (button.dataset.action) {
    case "new": openTemplate(null); break;
    case "back": guarded(listPage); break;
    case "clear-filter": filter = "all"; query = ""; render(); break;
    case "retry": lastScenario = "normal"; $("#scenario").value = "normal"; render(); break;
    case "save": void save(); break;
    case "copy": if (dirty()) confirmAction("复制已保存的配置？", "复制以最近保存的配置为准。继续会放弃当前修改；如需保留，请先保存。", "放弃修改并复制", () => openTemplate(draft.id, true)); else openTemplate(draft.id, true); break;
    case "delete": confirmAction(`删除“${draft.name}”？`, "删除后，这份模板配置将无法恢复。模型和 Skill 保留。", "删除模板", () => { templates = templates.filter((item) => item.id !== draft.id); listPage(); toast("模板已删除（原型内存）。"); }, true); break;
  }
});
$("#app").addEventListener("input", (event) => {
  const target = event.target;
  if (target.id === "template-search") { query = target.value; renderCards(); return; }
  if (target.id === "skill-search") { skillQuery = target.value; renderSkills(); return; }
  if (!target.dataset.field || blocked()) return;
  const key = target.dataset.field;
  if (target.type === "checkbox") draft[key] = target.checked;
  else if (["modelId", "summaryModelId", "temperature", "contextBudgetTokens"].includes(key)) draft[key] = target.validity.badInput ? "invalid" : target.value === "" ? null : Number(target.value);
  else if (["maxIterations", "timeoutMinutes", "keepRecentMessages"].includes(key)) draft[key] = target.value === "" ? "" : Number(target.value);
  else draft[key] = target.value;
  target.removeAttribute("aria-invalid");
  if (key === "prompt") $("#prompt-count").textContent = `${draft.prompt.length.toLocaleString()} / 20,000`;
  if (["modelId", "summaryModelId", "contextBudgetTokens"].includes(key)) updateDerived();
  if (validationShown) showValidation();
  updateDirty();
});
$("#app").addEventListener("change", (event) => {
  const target = event.target;
  if (blocked()) return;
  if (target.dataset.bindSkill) {
    const id = Number(target.dataset.bindSkill);
    if (target.checked && !draft.skills.some((item) => item.id === id) && !unavailableSkill(id)) draft.skills.push({ id, version: skillById(id).latest });
    else if (!target.checked) draft.skills = draft.skills.filter((item) => item.id !== id);
    renderSkills(); updateDerived(); updateDirty();
    $(`[data-bind-skill="${id}"]`)?.focus();
  }
  if (target.dataset.skillVersion) { draft.skills.find((item) => item.id === Number(target.dataset.skillVersion)).version = Number(target.value); renderSkills(); updateDirty(); $(`[data-skill-version="${target.dataset.skillVersion}"]`)?.focus(); }
});
$("#app").addEventListener("submit", (event) => { event.preventDefault(); void save(); });
$("#app").addEventListener("keydown", (event) => {
  if (event.target.dataset.configTab && ["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) {
    event.preventDefault();
    const index = tabs.findIndex(([key]) => key === activeTab);
    const next = event.key === "Home" ? 0 : event.key === "End" ? tabs.length - 1 : (index + (event.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
    switchTab(tabs[next][0], true);
  }
  if (event.target.tagName === "TEXTAREA" && event.key === "Tab" && !event.shiftKey && !blocked()) {
    event.preventDefault();
    event.target.setRangeText("  ", event.target.selectionStart, event.target.selectionEnd, "end");
    event.target.dispatchEvent(new Event("input", { bubbles: true }));
  }
  if ((event.ctrlKey || event.metaKey) && event.key === "s" && page === "editor") { event.preventDefault(); void save(); }
});
$("#confirm-cancel").addEventListener("click", () => { pendingConfirm = null; $("#confirm-dialog").close(); returnFocus?.focus(); });
$("#confirm-dialog").addEventListener("cancel", () => { pendingConfirm = null; });
$("#confirm-accept").addEventListener("click", () => { const callback = pendingConfirm; pendingConfirm = null; $("#confirm-dialog").close(); callback?.(); });
$("#nav-templates").addEventListener("click", () => guarded(listPage));
$("#readonly").addEventListener("change", (event) => {
  const desired = event.target.checked;
  event.target.checked = readOnly;
  guarded(() => { const previousTab = activeTab; if (page === "editor") { if (draft.id) openTemplate(draft.id, false, previousTab); else listPage(); } readOnly = desired; event.target.checked = desired; render(); });
});
$("#scenario").addEventListener("change", (event) => {
  const desired = event.target.value;
  event.target.value = lastScenario;
  guarded(() => { lastScenario = desired; event.target.value = desired; if (page === "editor") { if (draft.id) openTemplate(draft.id, false, activeTab); else listPage(); } render(); });
});
window.addEventListener("beforeunload", (event) => { if (dirty() || busy) { event.preventDefault(); event.returnValue = ""; } });
const initial = new URLSearchParams(location.search);
if (initial.get("view") === "editor") openTemplate(101, false, initial.get("tab") ?? "basic");
else render();
