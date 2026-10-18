// 所有数据仅用于页面讨论；没有网络调用和浏览器持久化。
const initial = {
  sources: [
    {
      id: "deepseek",
      name: "DeepSeek 开发环境",
      supplier: "DeepSeek",
      protocol: "openai",
      baseUrl: "https://api.example.test/deepseek/v1",
      hasKey: true,
      enabled: true,
    },
    {
      id: "bailian",
      name: "百炼主账号",
      supplier: "阿里云百炼",
      protocol: "openai",
      baseUrl: "https://api.example.test/bailian/v1",
      hasKey: true,
      enabled: true,
    },
  ],
  models: [
    {
      id: "pro",
      sourceId: "deepseek",
      name: "DeepSeek 4 Pro（示例）",
      modelId: "demo-deepseek-v4-pro",
      enabled: true,
      origin: "手动添加",
      refCount: 1,
    },
    {
      id: "flash",
      sourceId: "deepseek",
      name: "DeepSeek 4.1 Flash（示例）",
      modelId: "demo-deepseek-v4.1-flash",
      enabled: true,
      origin: "供应商查询",
      refCount: 0,
    },
    {
      id: "qwen",
      sourceId: "bailian",
      name: "Qwen Plus（示例）",
      modelId: "demo-qwen-plus",
      enabled: true,
      origin: "供应商查询",
      refCount: 0,
    },
  ],
};
const state = structuredClone(initial);
let selectedId = "deepseek";
let role = "owner";
let editor = null;
let deleteTarget = null;
let search = "";
let importIds = new Set();
let toastTimer;
const $ = (id) => document.getElementById(id);
const safe = (value) =>
  String(value).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const canEdit = () => role !== "member";
const source = () => state.sources.find((item) => item.id === selectedId);
const sourceModels = () =>
  state.models.filter((item) => item.sourceId === selectedId);
const protocolName = (value) =>
  value === "anthropic" ? "Anthropic 兼容" : "OpenAI 兼容";
const badge = (enabled) =>
  `<span class="tag ${enabled ? "success" : ""}">${enabled ? "已启用" : "已停用"}</span>`;
const action = (name, label, id = "", classes = "") =>
  `<button type="button" class="${classes}" data-action="${name}" data-id="${safe(id)}">${label}</button>`;
const uid = () => crypto.randomUUID();

function notify(message) {
  clearTimeout(toastTimer);
  $("toast").textContent = message;
  $("toast").hidden = false;
  toastTimer = setTimeout(() => {
    $("toast").hidden = true;
  }, 3500);
}

function render() {
  const current = source();
  const editable = canEdit();
  $("heading-actions").innerHTML =
    `<span class="tag outline">${role === "member" ? "普通成员 · 只读" : role === "admin" ? "全局负责人" : "租户负责人"}</span>${editable ? action("add-source", "＋ 新增来源", "", "primary") : ""}`;
  $("source-count").textContent = `${state.sources.length} 条连接`;
  $("source-list").className = "source-list-grid";
  $("source-list").innerHTML =
    state.sources
      .map(
        (item) =>
          `<button type="button" class="source-card ${item.id === selectedId ? "selected" : ""}" data-action="select-source" data-id="${safe(item.id)}" aria-pressed="${item.id === selectedId}"><div class="source-top"><div><div class="source-name">${safe(item.name)}</div><p class="small muted">${safe(item.supplier)} · ${protocolName(item.protocol)}</p></div><span class="dot ${item.enabled ? "" : "off"}" aria-hidden="true"></span></div><div class="source-meta"><span>${state.models.filter((model) => model.sourceId === item.id).length} 个模型</span><span>${item.enabled ? "已启用" : "已停用"}</span></div></button>`,
      )
      .join("") +
    (editable ? action("add-source", "＋ 添加另一条连接", "", "wide") : "");
  if (!current) {
    $("detail").innerHTML =
      `<div class="card empty"><h2>先添加一个供应商来源</h2><p>来源保存连接信息，再在来源下维护模型。</p></div>`;
    return;
  }
  const models = sourceModels();
  $("detail").innerHTML =
    `<div class="card source-detail"><div class="card-heading"><div><div class="inline"><h2>${safe(current.name)}</h2>${badge(current.enabled)}</div><p class="small muted">${safe(current.supplier)} · ${protocolName(current.protocol)}</p></div>${editable ? `<div class="toolbar">${action("toggle-source", current.enabled ? "停用来源" : "启用来源")}${action("edit-source", "编辑")}${action("delete-source", "删除", "", "danger ghost")}</div>` : ""}</div><dl class="connection-fields"><div><dt>API 地址</dt><dd class="mono">${safe(current.baseUrl)}</dd></div><div><dt>API Key</dt><dd class="inline"><span class="mono">${current.hasKey ? "••••••••••••••••" : "尚未配置"}</span>${current.hasKey ? '<span class="tag outline">已配置</span>' : ""}</dd></div></dl></div><div class="model-heading"><div><div class="inline"><h2>模型列表</h2><span class="tag">${models.length}</span></div><p class="small muted">只管理模型身份和可用状态，运行参数归 Agent 模板。</p></div>${editable ? `<div class="toolbar">${action("lookup", "↓ 查询供应商模型")}${action("add-model", "＋ 手动添加", "", "primary")}</div>` : ""}</div><div class="card"><div class="table-toolbar"><label for="model-search" class="sr-only">搜索当前模型</label><input id="model-search" placeholder="搜索模型名称或 ID" value="${safe(search)}" /><span class="small muted">${models.filter((item) => item.enabled).length} 个已启用</span></div><div class="table-scroll"><table><thead><tr><th>模型名称</th><th>模型 ID</th><th>添加方式</th><th>状态</th>${editable ? "<th>操作</th>" : ""}</tr></thead><tbody id="model-rows"></tbody></table></div><p class="table-footer">模板引用数为模拟数据，用来演示删除保护。已被引用的模型可以停用，不能直接删除。</p></div>`;
  renderRows();
}

function renderRows() {
  const rows = sourceModels().filter((item) =>
    `${item.name} ${item.modelId}`.toLowerCase().includes(search.toLowerCase()),
  );
  $("model-rows").innerHTML =
    rows
      .map(
        (item) =>
          `<tr><td><strong>${safe(item.name)}</strong>${item.refCount ? `<div class="small muted">模板引用 ${item.refCount}（演示）</div>` : ""}</td><td class="mono">${safe(item.modelId)}</td><td><span class="tag outline">${item.origin}</span></td><td>${badge(item.enabled)}</td>${canEdit() ? `<td class="actions">${action("toggle-model", item.enabled ? "停用" : "启用", item.id, "ghost")}${action("edit-model", "编辑", item.id, "ghost")}${action("delete-model", "删除", item.id, "ghost danger")}</td>` : ""}</tr>`,
      )
      .join("") ||
    `<tr><td colspan="${canEdit() ? 5 : 4}" class="empty">${search ? "没有匹配的模型" : "还没有添加模型，可查询供应商或手动添加。"}</td></tr>`;
}

const inputField = (label, name, value = "", extra = "") =>
  `<label class="field">${label}<input name="${name}" value="${safe(value)}" ${extra} /></label>`;

function openEditor(type, item = null) {
  if (!canEdit()) return;
  editor = { type, id: item?.id };
  $("form-error").textContent = "";
  $("editor-title").textContent =
    type === "source"
      ? item
        ? "编辑供应商连接"
        : "新增供应商来源"
      : item
        ? "编辑模型"
        : "手动添加模型";
  $("editor-description").textContent =
    type === "source"
      ? "连接属于当前租户；API Key 留空表示保留。"
      : "模型名称用于显示，模型 ID 用于供应商调用。";
  if (type === "source") {
    $("editor-fields").innerHTML =
      inputField(
        "供应商名称",
        "supplier",
        item?.supplier ?? "",
        'required maxlength="80" placeholder="例如：DeepSeek、阿里云百炼"',
      ) +
      inputField(
        "连接名称",
        "name",
        item?.name ?? "",
        'required maxlength="80" placeholder="例如：DeepSeek 生产环境"',
      ) +
      `<label class="field">接口协议<select name="protocol"><option value="openai" ${item?.protocol !== "anthropic" ? "selected" : ""}>OpenAI 兼容</option><option value="anthropic" ${item?.protocol === "anthropic" ? "selected" : ""}>Anthropic 兼容</option></select></label>` +
      inputField(
        "API 地址",
        "baseUrl",
        item?.baseUrl ?? "",
        'required type="url" placeholder="https://api.example.test/v1"',
      ) +
      `<label class="field">API Key<input name="apiKey" type="password" autocomplete="new-password" placeholder="${item?.hasKey ? "已配置，留空保持不变" : "此处仅作演示，请勿输入真实密钥"}" /></label>${item?.hasKey ? '<label class="checkbox-field"><input type="checkbox" name="clearKey" />清除已保存的 API Key</label>' : ""}<p class="small muted">原型只保留是否配置的标记；输入值不会写入状态或持久存储。</p>`;
  } else {
    $("editor-fields").innerHTML =
      inputField(
        "模型名称",
        "name",
        item?.name ?? "",
        'required maxlength="80" placeholder="例如：Qwen Plus"',
      ) +
      inputField(
        "模型 ID",
        "modelId",
        item?.modelId ?? "",
        'required maxlength="160" placeholder="供应商定义的模型标识"',
      ) +
      '<p class="notice">温度、Token 上限等参数在后续 Agent 模板中配置。</p>';
  }
  $("editor").showModal();
}

$("editor-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (!canEdit() || !editor) return;
  const data = new FormData(event.currentTarget);
  const name = String(data.get("name") ?? "").trim();
  const fail = (message) => {
    $("form-error").textContent = message;
  };
  if (!name) return fail("请输入名称，不能只包含空格。");
  if (editor.type === "source") {
    const old = state.sources.find((item) => item.id === editor.id);
    const supplier = String(data.get("supplier") ?? "").trim();
    if (!supplier) return fail("请输入供应商名称。");
    if (
      state.sources.some((item) => item.id !== editor.id && item.name === name)
    )
      return fail("当前租户内连接名称已存在。");
    const baseUrl = String(data.get("baseUrl") ?? "").trim();
    let url;
    try {
      url = new URL(baseUrl);
    } catch {
      return fail("请输入有效的 API 根地址。");
    }
    if (
      !["http:", "https:"].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash ||
      /\/(models|messages|chat\/completions)\/?$/.test(url.pathname)
    )
      return fail("API 地址需为 HTTP(S) 根地址，不包含账号、查询串或片段。");
    if (
      data.get("clearKey") === "on" &&
      String(data.get("apiKey") ?? "").trim()
    )
      return fail("更换和清除 API Key 不能同时进行，请选择一种操作。");
    const saved = {
      id: old?.id ?? uid(),
      name,
      supplier,
      baseUrl,
      protocol: String(data.get("protocol")),
      hasKey:
        data.get("clearKey") === "on"
          ? false
          : Boolean(String(data.get("apiKey") ?? "").trim()) ||
            Boolean(old?.hasKey),
      enabled: old?.enabled ?? true,
    };
    if (old) state.sources.splice(state.sources.indexOf(old), 1, saved);
    else state.sources.push(saved);
    selectedId = saved.id;
  } else {
    const modelId = String(data.get("modelId") ?? "").trim();
    if (!modelId) return fail("请输入模型 ID。");
    if (
      sourceModels().some(
        (item) => item.id !== editor.id && item.modelId === modelId,
      )
    )
      return fail("当前来源下已存在相同模型 ID。");
    const old = state.models.find((item) => item.id === editor.id);
    const saved = {
      id: old?.id ?? uid(),
      sourceId: selectedId,
      name,
      modelId,
      enabled: old?.enabled ?? true,
      origin: old?.origin ?? "手动添加",
      refCount: old?.refCount ?? 0,
    };
    if (old) state.models.splice(state.models.indexOf(old), 1, saved);
    else state.models.push(saved);
  }
  $("editor-form").reset();
  $("editor").close();
  render();
  notify("已保存页面示例数据");
});

function candidates() {
  const supplier = source()?.supplier ?? "";
  const existing = sourceModels().map(({ name, modelId }) => ({
    name,
    modelId,
  }));
  return [
    ...existing,
    {
      name: `${supplier} 额外模型（示例）`,
      modelId: `demo-${selectedId}-extra`,
    },
    { name: `${supplier} 新模型（示例）`, modelId: `demo-${selectedId}-new` },
  ];
}

function renderLookup() {
  const scenario = $("lookup-scenario").value;
  const keyword = $("lookup-search").value.toLowerCase();
  $("import-selected").disabled = !importIds.size || scenario !== "success";
  $("import-selected").textContent =
    `添加所选模型${importIds.size ? `（${importIds.size}）` : ""}`;
  if (scenario !== "success") {
    $("lookup-results").innerHTML =
      `<p class="lookup-message">${scenario === "unsupported" ? "供应商未提供模型列表接口。可以使用下方“手动添加”继续配置。" : "模拟查询失败：连接不可用或凭证无效。已保存的模型不受影响，可检查连接后重试或手动添加。"}</p>`;
    return;
  }
  const rows = candidates().filter((item) =>
    `${item.name} ${item.modelId}`.toLowerCase().includes(keyword),
  );
  $("lookup-results").innerHTML =
    rows
      .map((item) => {
        const added = sourceModels().some(
          (model) => model.modelId === item.modelId,
        );
        return `<label class="lookup-row"><input type="checkbox" data-model-id="${safe(item.modelId)}" ${added ? "disabled checked" : importIds.has(item.modelId) ? "checked" : ""} /><span class="name"><strong>${safe(item.name)}</strong><span class="small mono muted" style="display:block">${safe(item.modelId)}</span></span>${added ? '<span class="tag">已添加</span>' : ""}</label>`;
      })
      .join("") || '<p class="lookup-message">没有匹配的候选模型。</p>';
}

function requestDelete(type, id) {
  const item =
    type === "source"
      ? source()
      : state.models.find((model) => model.id === id);
  const refs =
    type === "source"
      ? sourceModels().reduce((sum, model) => sum + model.refCount, 0)
      : item.refCount;
  if (refs) return notify("模拟删除保护：已被模板引用，请改为停用。");
  deleteTarget = { type, id: item.id };
  $("delete-message").textContent =
    `确定删除“${item.name}”？${type === "source" ? "该来源下未被引用的模型将一并删除。" : ""}此操作仅影响页面示例数据。`;
  $("delete-dialog").showModal();
}

document.addEventListener("click", (event) => {
  const closeButton = event.target.closest("[data-close]");
  if (closeButton) return $(closeButton.dataset.close).close();
  const button = event.target.closest("[data-action]");
  if (!button) return;
  const { action: command, id } = button.dataset;
  if (command === "select-source") {
    selectedId = id;
    search = "";
    render();
    return;
  }
  if (!canEdit()) return;
  const current = source();
  if (command === "add-source") openEditor("source");
  if (command === "edit-source") openEditor("source", current);
  if (command === "delete-source") requestDelete("source");
  if (command === "toggle-source") {
    current.enabled = !current.enabled;
    render();
    notify(current.enabled ? "来源已启用" : "来源已停用，引用仍然保留");
  }
  if (command === "add-model" || command === "lookup-manual") {
    if ($("lookup").open) $("lookup").close();
    openEditor("model");
  }
  if (command === "edit-model")
    openEditor(
      "model",
      state.models.find((item) => item.id === id),
    );
  if (command === "toggle-model") {
    const model = state.models.find((item) => item.id === id);
    model.enabled = !model.enabled;
    render();
    notify(model.enabled ? "模型已启用" : "模型已停用，引用仍然保留");
  }
  if (command === "delete-model") requestDelete("model", id);
  if (command === "lookup") {
    importIds = new Set();
    $("lookup-search").value = "";
    $("lookup-scenario").value = "success";
    renderLookup();
    $("lookup").showModal();
  }
  if (command === "import") {
    const additions = candidates().filter(
      (item) =>
        importIds.has(item.modelId) &&
        !sourceModels().some((model) => model.modelId === item.modelId),
    );
    state.models.push(
      ...additions.map((item) => ({
        ...item,
        id: uid(),
        sourceId: selectedId,
        enabled: true,
        origin: "供应商查询",
        refCount: 0,
      })),
    );
    $("lookup").close();
    render();
    notify(`已添加 ${additions.length} 个示例模型`);
  }
  if (command === "confirm-delete" && deleteTarget) {
    const { type, id: targetId } = deleteTarget;
    if (type === "source") {
      state.sources = state.sources.filter((item) => item.id !== targetId);
      state.models = state.models.filter((item) => item.sourceId !== targetId);
      selectedId = state.sources[0]?.id;
    } else state.models = state.models.filter((item) => item.id !== targetId);
    deleteTarget = null;
    $("delete-dialog").close();
    render();
    notify("示例数据已删除");
  }
});
document.addEventListener("input", (event) => {
  if (event.target.id === "model-search") {
    search = event.target.value;
    renderRows();
  }
  if (event.target.id === "lookup-search") renderLookup();
});
document.addEventListener("change", (event) => {
  if (event.target.id === "role") {
    role = event.target.value;
    document
      .querySelectorAll("dialog[open]")
      .forEach((dialog) => dialog.close());
    render();
  }
  if (event.target.id === "lookup-scenario") {
    importIds.clear();
    renderLookup();
  }
  if (event.target.dataset.modelId) {
    if (event.target.checked) importIds.add(event.target.dataset.modelId);
    else importIds.delete(event.target.dataset.modelId);
    $("import-selected").disabled = !importIds.size;
    $("import-selected").textContent =
      `添加所选模型${importIds.size ? `（${importIds.size}）` : ""}`;
  }
});
$("editor").addEventListener("close", () => {
  $("editor-form").reset();
  $("editor-fields").replaceChildren();
  editor = null;
});
render();
