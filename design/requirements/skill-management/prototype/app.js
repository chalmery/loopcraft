const $ = (id) => document.getElementById(id);
const clone = (x) => structuredClone(x);
function sample(slug, name, description, tags) {
  return {
    slug,
    name,
    description,
    tags,
    enabled: true,
    origin: "CUSTOM",
    versions: [
      {
        number: 1,
        createdAt: "2026-10-21 09:30",
        files: {
          "SKILL.md": `---\nname: package-example\ndescription: 包内描述，仅作为文件内容保存。\n---\n\n# ${name}\n\n## 适用场景\n\n${description}\n\n## 工作步骤\n\n1. 确认任务目标与输入材料。\n2. 阅读 references/guide.md。\n3. 整理结果并核查。\n`,
          "references/guide.md":
            "# 参考规范\n\n保留来源，对未核实的内容明确标注。\n",
          "scripts/check.py":
            "# 示例脚本，原型不执行\ndef check(text):\n    return bool(text.strip())\n",
        },
      },
    ],
  };
}
const data = {
  a: [
    sample(
      "document-review",
      "文档审阅",
      "审阅文档结构、术语和表达，给出可执行的修改建议。",
      ["文档", "质量检查"],
    ),
    sample(
      "report-writing",
      "报告撰写",
      "从输入材料中提炼要点，整理成结构清晰的业务报告。",
      ["写作", "报告"],
    ),
    sample("meeting-notes", "会议纪要", "提取会议结论、待办事项与负责人。", [
      "协作",
    ]),
    sample("data-analysis", "数据分析", "梳理数据口径，识别异常并解释变化。", [
      "分析",
    ]),
  ],
  b: [
    sample(
      "knowledge-organizer",
      "知识整理",
      "将零散资料归纳为可检索的知识条目。",
      ["知识"],
    ),
  ],
};
data.a[0].versions.push({
  ...clone(data.a[0].versions[0]),
  number: 2,
  createdAt: "2026-10-22 10:30",
});
data.a[0].versions[1].files["SKILL.md"] += "\n4. 按影响程度整理修改建议。\n";
data.a[0].origin = "SYSTEM";
data.a.push(
  sample(
    "code-review",
    "代码审查",
    "检查代码的正确性、可维护性与边界条件，按影响程度整理审查意见。",
    ["开发", "质量检查"],
  ),
  sample(
    "research-brief",
    "资料研究",
    "梳理问题背景，对照来源核实信息，形成有据可查的研究简报。",
    ["研究", "资料整理"],
  ),
);
data.a[3].enabled = false;
let sourceFilter = "all",
  layout = "grid";
let tagDraft = [];
let metadataEditing = false;
let tenant = "a",
  current = null,
  versionIndex = 0,
  draft = null,
  isNew = false,
  path = "SKILL.md",
  metaDirty = false,
  pending = null,
  timer;
const read = () =>
  $("mode").value === "read" ||
  (current?.origin === "SYSTEM" &&
    !["owner", "admin"].includes($("mode").value));
const version = () => current.versions[versionIndex];
const dirty = () => !!draft || metaDirty;
function toast(message) {
  $("toast").textContent = message;
  $("toast").hidden = false;
  clearTimeout(timer);
  timer = setTimeout(() => ($("toast").hidden = true), 3000);
}
function guard(action) {
  if (!dirty()) {
    action();
    return;
  }
  pending = action;
  $("leave-dialog").showModal();
}
function element(tag, text, cls) {
  const e = document.createElement(tag);
  e.textContent = text;
  if (cls) e.className = cls;
  return e;
}
function list() {
  document.body.classList.remove("creating");
  current = null;
  draft = null;
  isNew = false;
  metaDirty = false;
  $("listing").hidden = false;
  $("detail").hidden = true;
  $("breadcrumb").textContent = "工作区 / Skill 管理";
  renderCards();
}
function renderCards() {
  const scenario = $("scenario").value,
    all = scenario === "empty" ? [] : data[tenant];
  const q = $("search").value.trim().toLowerCase();
  const items = all.filter(
    (s) =>
      (sourceFilter === "all" || s.origin === sourceFilter) &&
      `${s.name} ${s.slug} ${s.description} ${s.tags.join(" ")}`
        .toLowerCase()
        .includes(q),
  );
  const unavailable = scenario === "error" || scenario === "loading";
  $("cards").replaceChildren();
  $("cards").hidden = unavailable;
  $("cards").classList.toggle("list-view", layout === "list");
  $("count").textContent = unavailable
    ? scenario === "loading"
      ? "正在加载…"
      : "加载失败"
    : `${q || sourceFilter !== "all" ? "找到" : "共"} ${items.length} 个 Skill`;
  $("total-count").textContent = unavailable ? "—" : all.length;
  $("all-count").textContent = all.length;
  $("custom-count").textContent = all.filter(
    (s) => s.origin === "CUSTOM",
  ).length;
  $("system-count").textContent = all.filter(
    (s) => s.origin === "SYSTEM",
  ).length;
  $("empty").hidden = unavailable || items.length > 0;
  $("list-error").hidden = scenario !== "error";
  $("list-loading").hidden = scenario !== "loading";
  const filtered = all.length > 0;
  $("empty-title").textContent = filtered
    ? "没有找到匹配的 Skill"
    : "创建你的第一个 Skill";
  $("empty-description").textContent = filtered
    ? "换个关键词，或清除筛选后查看全部 Skill。"
    : "从一份清晰的指令开始，把常用的工作方法保存下来。";
  $("empty-actions").hidden = !filtered;
  $("clear-search").hidden = !filtered;
  $("empty-footnote").hidden = filtered;
  const readOnly = $("mode").value === "read";
  ["create", "import"].forEach((id) => ($(id).disabled = readOnly));
  document.querySelectorAll("[data-filter]").forEach((button) => {
    const selected = button.dataset.filter === sourceFilter;
    button.classList.toggle("selected", selected);
    button.setAttribute("aria-pressed", String(selected));
  });
  if (unavailable) return;
  for (const skill of items) {
    const card = element("article", "", "card"),
      top = element("div", "", "card-top");
    top.append(
      element(
        "span",
        skill.origin === "SYSTEM" ? "系统内置" : "工作区自建",
        "origin-label",
      ),
      element(
        "span",
        skill.enabled ? "已启用" : "已禁用",
        "status" + (skill.enabled ? "" : " off"),
      ),
    );
    const title = element("div", "", "card-title"),
      heading = element("h2", skill.name),
      link = element("button", "", "card-open");
    link.type = "button";
    link.title = skill.name;
    link.setAttribute("aria-label", `查看 ${skill.name} 详情`);
    link.onclick = () => open(skill);
    card.append(link);
    title.append(heading);
    const slug = element("div", skill.slug, "slug");
    slug.title = skill.slug;
    card.append(
      top,
      title,
      slug,
      element("p", skill.description, "card-description"),
    );
    const tags = element("div", "", "tags");
    skill.tags.forEach((tag) => tags.append(element("span", tag, "tag")));
    card.append(tags);
    const latest = skill.versions.at(-1),
      bottom = element("div", "", "card-bottom");
    bottom.append(
      element(
        "span",
        `v${latest.number} · ${Object.keys(latest.files).length} 个文件`,
        "version-label",
      ),
      element(
        "span",
        latest.createdAt.slice(5, 10).replace("-", "/") + " 更新",
        "card-updated",
      ),
    );
    card.append(bottom);
    $("cards").append(card);
  }
}

function open(s) {
  current = s;
  versionIndex = s.versions.length - 1;
  isNew = false;
  metadataEditing = false;
  draft = null;
  metaDirty = false;
  showDetail("info");
}
function showDetail(tab) {
  const editingMetadata = isNew || (metadataEditing && !read());
  $("metadata-fields").hidden = !editingMetadata;
  $("metadata-summary").hidden = editingMetadata;
  $("edit-meta").hidden = isNew || metadataEditing || read();
  $("cancel-meta").hidden = !metadataEditing || isNew;
  $("save-meta").hidden = !editingMetadata;
  $("metadata-summary").replaceChildren();
  const fields = [
    ["显示名称", current.name],
    ["Skill 标识", current.slug],
    ["来源", current.origin === "SYSTEM" ? "系统内置" : "租户自建"],
    ["状态", current.enabled ? "启用" : "禁用"],
    ["描述", current.description],
    ["标签", current.tags.join(" · ") || "暂无标签"],
  ];
  for (const [label, value] of fields) {
    const field = element("div");
    field.append(element("dt", label), element("dd", value));
    $("metadata-summary").append(field);
  }
  $("draft-actions").hidden = !isNew;
  $("draft-hint").hidden = !isNew;
  $("latest").hidden = isNew;
  document.body.classList.toggle("creating", isNew);
  $("listing").hidden = true;
  $("detail").hidden = false;
  $("breadcrumb").textContent = "工作区 / Skill 管理 / 详情";
  $("detail-name").textContent = isNew
    ? current.slug
      ? "编辑导入的 Skill"
      : "新增 Skill"
    : current.name;
  $("detail-slug").textContent = isNew
    ? "填写基本信息，编写内容后创建。"
    : current.slug;
  $("latest").textContent = isNew
    ? "尚未创建"
    : `最新 v${current.versions.at(-1).number}`;
  $("meta-name").value = current.name;
  $("meta-slug").value = current.slug;
  $("meta-slug").readOnly = !isNew || read();
  $("meta-description").value = current.description;
  $("meta-origin").value =
    current.origin === "SYSTEM" ? "系统内置" : "租户自建";
  $("meta-enabled").value = String(current.enabled);
  $("meta-enabled").disabled = read();
  tagDraft = [...current.tags];
  $("tag-input").value = "";
  renderTagEditor();
  ["meta-name", "meta-description"].forEach((id) => ($(id).readOnly = read()));
  $("save-meta").disabled = read();
  $("save-meta").textContent = isNew ? "保存并创建 v1" : "保存基本信息";
  $("metadata-hint").textContent = isNew
    ? "导入时复用 SKILL.md 的 name 与 description，可修改后保存；更改不会重写包内文件。"
    : "基本信息与包内文件独立保存，互不自动覆盖。";
  $("meta-error").textContent = "";
  switchTab(tab);
  renderVersions();
  renderVersionList();
}
function switchTab(tab) {
  $("info-panel").hidden = tab !== "info";
  $("content-panel").hidden = tab !== "content";
  $("tab-info").classList.toggle("selected", tab === "info");
  $("tab-content").classList.toggle("selected", tab === "content");
  $("tab-info").setAttribute("aria-pressed", String(tab === "info"));
  $("tab-content").setAttribute("aria-pressed", String(tab === "content"));
}
function renderVersions() {
  const select = $("version");
  select.replaceChildren();
  if (isNew) {
    select.append(new Option("v1 · 待保存", "0"));
  } else
    current.versions.forEach((v, i) =>
      select.append(new Option(`v${v.number}`, String(i))),
    );
  select.value = String(versionIndex);
  select.disabled = !!draft;
  $("edit-content").hidden =
    !!draft || (!isNew && versionIndex !== current.versions.length - 1);
  $("edit-content").disabled = read();
  $("save-version").hidden = !draft;
  $("save-version").textContent = isNew
    ? "保存并创建 v1"
    : `保存为 v${current.versions.at(-1).number + 1}`;
  $("cancel-edit").hidden = !draft;
  $("new-file").hidden = !draft;
  $("version-note").textContent = draft
    ? "正在编辑 · 保存后生成新版本"
    : versionIndex === current.versions.length - 1
      ? "最新版本"
      : "历史版本 · 仅查看";
  $("snapshot").textContent = `当前 Skill：${current.slug}`;
  $("version-error").textContent = "";
  renderFiles();
}
function renderVersionList() {
  $("version-count").textContent = `${current.versions.length} 个版本`;
  $("version-rows").replaceChildren();
  $("no-versions").hidden = current.versions.length > 0;
  current.versions
    .map((v, index) => ({ v, index }))
    .reverse()
    .forEach(({ v, index }) => {
      const row = element("tr"),
        name = element("td", `v${v.number}`);
      if (index === current.versions.length - 1)
        name.append(element("span", "最新", "badge"));
      row.append(
        name,
        element("td", String(Object.keys(v.files).length)),
        element("td", v.createdAt),
      );
      const action = element("td", "", "action-cell"),
        button = element("button", "查看详情", "version-link");
      button.setAttribute("aria-label", `查看 v${v.number} 内容详情`);
      button.onclick = () =>
        guard(() => {
          metaDirty = false;
          draft = null;
          versionIndex = index;
          path = "SKILL.md";
          showDetail("content");
          $("tab-content").focus();
        });
      action.append(button);
      row.append(action);
      $("version-rows").append(row);
    });
}
function renderFiles() {
  const files = draft || version().files;
  if (!(path in files)) path = "SKILL.md";
  $("files").replaceChildren();
  Object.keys(files).forEach((p) => {
    const b = element("button", p, "file" + (p === path ? " selected" : ""));
    b.onclick = () => {
      path = p;
      renderFiles();
    };
    $("files").append(b);
  });
  $("filepath").textContent = path;
  $("body").value = files[path] ?? "";
  renderLineNumbers();
  $("body").readOnly = !draft;
  $("body").closest(".code-area").hidden = files[path] === null;
  $("binary").hidden = files[path] !== null;
  $("edit-state").textContent = draft ? "编辑中" : "只读";
  $("file-hint").textContent =
    path === "SKILL.md"
      ? "SKILL.md 按文件原文编辑，包内字段不与平台基本信息同步。"
      : "此文件随整个包一起生成版本。";
}
function renderLineNumbers() {
  $("line-numbers").textContent = Array.from(
    { length: $("body").value.split("\n").length },
    (_, i) => i + 1,
  ).join("\n");
}
$("body").oninput = () => {
  if (draft) draft[path] = $("body").value;
  renderLineNumbers();
};
$("body").onscroll = () => {
  $("line-numbers").scrollTop = $("body").scrollTop;
};
$("edit-content").onclick = () => {
  if (read() || versionIndex !== current.versions.length - 1) return;
  draft = clone(version().files);
  renderVersions();
};
$("cancel-edit").onclick = () =>
  guard(() => {
    if (isNew) list();
    else {
      draft = null;
      renderVersions();
    }
  });
$("save-version").onclick = () => {
  if (read() || !draft) return;
  if (isNew) {
    const slug = $("meta-slug").value.trim(),
      name = $("meta-name").value.trim(),
      description = $("meta-description").value.trim();
    if (
      !/^[a-z0-9]+(-[a-z0-9]+)*$/.test(slug) ||
      slug.length > 64 ||
      !name ||
      !description
    ) {
      $("meta-error").textContent = "请填写名称、描述和有效的 Skill 标识。";
      switchTab("info");
      return;
    }
    Object.assign(current, { slug, name, description, tags: [...tagDraft] });
  }
  if (!draft["SKILL.md"]?.trim()) {
    $("version-error").textContent = "SKILL.md 正文不能为空。";
    switchTab("content");
    return;
  }
  if ($("fail").checked) {
    $("version-error").textContent =
      "模拟保存失败，编辑内容已保留；没有创建新版本。";
    switchTab("content");
    return;
  }
  if (isNew && data[tenant].some((s) => s.slug === current.slug)) {
    $("meta-error").textContent = "此租户已存在相同标识。";
    switchTab("info");
    return;
  }
  const v = {
    createdAt: new Date()
      .toLocaleString("sv-SE", { timeZone: "Asia/Shanghai" })
      .slice(0, 16),
    number: isNew ? 1 : current.versions.at(-1).number + 1,
    files: clone(draft),
  };
  current.versions.push(v);
  if (isNew) data[tenant].push(current);
  isNew = false;
  draft = null;
  versionIndex = current.versions.length - 1;
  showDetail("content");
  toast(`v${v.number} 已保存到原型内存，历史版本保持不变`);
};
$("version").onchange = () => {
  versionIndex = Number($("version").value);
  path = "SKILL.md";
  renderVersions();
};
$("tab-info").onclick = () => {
  if (isNew) {
    switchTab("info");
    return;
  }
  guard(() => {
    draft = null;
    metaDirty = false;
    metadataEditing = false;
    showDetail("info");
  });
};
$("tab-content").onclick = () => {
  if (draft) {
    switchTab("content");
    return;
  }
  guard(() => {
    metaDirty = false;
    metadataEditing = false;
    showDetail("content");
  });
};
["meta-name", "meta-description", "meta-enabled"].forEach(
  (id) => ($(id).oninput = () => (metaDirty = true)),
);
function renderTagEditor() {
  $("tag-chips").replaceChildren();
  $("tag-input").disabled = read();
  $("add-tag").disabled = read();
  tagDraft.forEach((tag, index) => {
    const chip = element("span", tag, "tag");
    if (!read()) {
      const remove = element("button", "×", "remove-tag");
      remove.type = "button";
      remove.setAttribute("aria-label", `移除标签 ${tag}`);
      remove.onclick = () => {
        tagDraft.splice(index, 1);
        metaDirty = true;
        renderTagEditor();
      };
      chip.append(remove);
    }
    $("tag-chips").append(chip);
  });
}
function addTag() {
  const text = $("tag-input").value.trim();
  if (!text || read()) return;
  if (!tagDraft.includes(text)) tagDraft.push(text);
  $("tag-input").value = "";
  metaDirty = true;
  renderTagEditor();
  $("tag-input").focus();
}
$("tag-input").onkeydown = (e) => {
  if (e.key === "Enter" && !e.isComposing) {
    e.preventDefault();
    addTag();
  }
};
$("tag-input").oninput = () => (metaDirty = true);
$("add-tag").onclick = addTag;
$("edit-meta").onclick = () => {
  metadataEditing = true;
  showDetail("info");
  $("meta-name").focus();
};
$("cancel-meta").onclick = () => {
  metadataEditing = false;
  metaDirty = false;
  showDetail("info");
  $("edit-meta").focus();
};
$("save-meta").onclick = () => {
  if (isNew) {
    $("save-version").onclick();
    return;
  }
  if (read()) return;
  const name = $("meta-name").value.trim(),
    description = $("meta-description").value.trim();
  if (!name || !description) {
    $("meta-error").textContent = "名称和描述不能为空。";
    return;
  }
  if ($("fail").checked) {
    $("meta-error").textContent = "模拟保存失败，输入内容已保留。";
    return;
  }
  Object.assign(current, {
    name,
    description,
    enabled: $("meta-enabled").value === "true",
    tags: [
      ...new Set([...tagDraft, $("tag-input").value.trim()].filter(Boolean)),
    ],
  });
  metaDirty = false;
  metadataEditing = false;
  showDetail("info");
  toast("基本信息已保存，内容版本数量不变");
};
function createDraft(imported) {
  const s = imported
    ? sample(
        "research-assistant",
        "research-assistant",
        "整理研究资料并生成包含来源的研究报告。",
        [],
      )
    : sample("", "", "", []);
  if (imported)
    s.versions[0].files["SKILL.md"] =
      "---\nname: research-assistant\ndescription: 整理研究资料并生成包含来源的研究报告。\n---\n\n# 研究助手\n\n1. 整理输入资料。\n2. 对照来源核查。\n3. 输出报告。\n";
  draft = imported
    ? clone(s.versions[0].files)
    : { "SKILL.md": "# 工作步骤\n\n请填写 Skill 指令。\n" };
  current = { ...s, versions: [] };
  isNew = true;
  versionIndex = 0;
  path = "SKILL.md";
  metaDirty = false;
  showDetail("info");
}
$("create").onclick = () => {
  if ($("mode").value !== "read") createDraft(false);
};
$("import").onclick = () => {
  $("zip").value = "";
  $("zip-status").textContent = "";
  $("zip-filename").hidden = true;
  $("new-dialog").showModal();
};
$("close-new").onclick = () => $("new-dialog").close();
$("choose-zip").onclick = () => $("zip").click();
$("zip").onchange = () => {
  const file = $("zip").files[0];
  $("zip-filename").hidden = !file;
  $("zip-filename").textContent = file?.name ?? "";
  $("zip-status").classList.remove("error");
  if (!file) {
    $("zip-status").textContent = "";
    return;
  }
  const invalid = !file.name.toLowerCase().endsWith(".zip")
    ? "请选择 ZIP 格式的文件。"
    : file.size > 20 * 1024 * 1024
      ? "文件不能超过 20 MiB。"
      : "";
  $("zip-status").textContent =
    invalid || "已选择文件，可使用下方示例包查看编辑流程。";
  $("zip-status").classList.toggle("error", !!invalid);
};
$("sample-zip").onclick = () => {
  $("new-dialog").close();
  createDraft(true);
};
$("new-file").onclick = () => {
  $("file-form").reset();
  $("path-error").textContent = "";
  $("file-dialog").showModal();
};
$("close-file").onclick = () => $("file-dialog").close();
$("file-form").onsubmit = (e) => {
  e.preventDefault();
  const p = $("file-path").value.trim();
  if (
    !p ||
    p.startsWith("/") ||
    p.includes("\\") ||
    p.split("/").some((x) => !x || x === "." || x === "..") ||
    Object.hasOwn(draft, p)
  ) {
    $("path-error").textContent =
      "请输入不重复的包内相对路径，不能包含 .. 或空目录。";
    return;
  }
  draft[p] = "";
  path = p;
  $("file-dialog").close();
  renderFiles();
};
$("draft-cancel").onclick = () => guard(list);
$("draft-save").onclick = () => $("save-version").onclick();
$("back").onclick = () => guard(list);
$("search").oninput = renderCards;
$("tenant").onchange = () => {
  const next = $("tenant").value;
  $("tenant").value = tenant;
  guard(() => {
    tenant = next;
    $("tenant").value = tenant;
    $("search").value = "";
    sourceFilter = "all";
    list();
  });
};
let mode = "edit";
$("mode").onchange = () => {
  const next = $("mode").value;
  $("mode").value = mode;
  guard(() => {
    mode = next;
    $("mode").value = mode;
    draft = null;
    metaDirty = false;
    if (isNew) list();
    else if (current) showDetail("info");
    else renderCards();
  });
};
$("stay").onclick = () => {
  pending = null;
  $("leave-dialog").close();
};
$("leave").onclick = () => {
  $("leave-dialog").close();
  const fn = pending;
  pending = null;
  fn?.();
};
$("leave-dialog").onclose = () => {
  pending = null;
};
document.querySelectorAll("[data-filter]").forEach(
  (button) =>
    (button.onclick = () => {
      sourceFilter = button.dataset.filter;
      renderCards();
    }),
);
function setLayout(next) {
  layout = next;
  ["grid", "list"].forEach((view) => {
    $(`${view}-view`).classList.toggle("selected", view === layout);
    $(`${view}-view`).setAttribute("aria-pressed", String(view === layout));
  });
  renderCards();
}
$("local-tab").onclick = () => {
  if (current) guard(list);
};
$("grid-view").onclick = () => setLayout("grid");
$("list-view").onclick = () => setLayout("list");
$("scenario").onchange = () => guard(() => list());
$("retry-list").onclick = () => {
  $("scenario").value = "normal";
  renderCards();
};
$("clear-search").onclick = () => {
  $("search").value = "";
  sourceFilter = "all";
  renderCards();
  $("search").focus();
};
window.addEventListener("beforeunload", (e) => {
  if (dirty()) {
    e.preventDefault();
    e.returnValue = "";
  }
});
list();
