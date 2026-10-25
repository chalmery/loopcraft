'use strict';

/* 全部数据仅在内存中；示例状态刻意覆盖正常、已停用、stdio 未启用与不可用四种形态。 */
const DB = {
  tenants: { a: '演示工作区 A', b: '演示工作区 B' },
  servers: {
    a: [
      {
        id: 's1',
        name: 'Context7 文档检索',
        slug: 'context7',
        transport: 'http-streamable',
        url: 'https://mcp.context7.com/mcp',
        auth: [{ name: 'Authorization', value: null }],
        enabled: true,
        connected: true,
        error: null,
        updatedAt: '2026-10-24 15:20',
        tools: [
          { name: 'resolve-library-id', desc: '按库名解析出上下文兼容的库 ID', params: '{ "libraryName": string }' },
          { name: 'get-library-docs', desc: '按库 ID 拉取最新文档片段', params: '{ "libraryId": string, "topic"?: string }' },
        ],
      },
      {
        id: 's2',
        name: 'DeepWiki 代码库百科',
        slug: 'deepwiki',
        transport: 'http-sse',
        url: 'https://mcp.deepwiki.com/sse',
        auth: [],
        enabled: true,
        connected: true,
        error: null,
        updatedAt: '2026-10-23 09:41',
        tools: [
          { name: 'read_wiki_structure', desc: '读取仓库百科的目录结构', params: '{ "repoName": string }' },
          { name: 'read_wiki_contents', desc: '读取指定章节内容', params: '{ "repoName": string, "page"?: string }' },
        ],
      },
      {
        id: 's3',
        name: '本地文件系统',
        slug: 'filesystem',
        transport: 'stdio',
        negotiated: null,
        command: 'npx',
        args: ['@modelcontextprotocol/server-filesystem', '/data'],
        env: ['FS_ROOT=/data'],
        enabled: true,
        connected: false,
        error: null,
        updatedAt: '2026-10-24 11:02',
        tools: [
          { name: 'list_directory', desc: '列出目录内容', params: '{ "path": string }' },
          { name: 'read_file', desc: '读取文本文件内容', params: '{ "path": string }' },
        ],
      },
      {
        id: 's4',
        name: '旧资料服务',
        slug: 'legacy-docs',
        transport: 'http-streamable',
        url: 'https://mcp.example.com/mcp',
        auth: [],
        enabled: true,
        connected: false,
        error: '握手超时（5s）',
        updatedAt: '2026-10-20 18:33',
        tools: [],
      },
    ],
    b: [
      {
        id: 's9',
        name: '时间服务',
        slug: 'time',
        transport: 'http-streamable',
        url: 'https://mcp.example.org/mcp',
        auth: [],
        enabled: true,
        connected: true,
        error: null,
        updatedAt: '2026-10-22 10:00',
        tools: [{ name: 'get_current_time', desc: '返回当前时间', params: '{ "timezone"?: string }' }],
      },
    ],
  },
  templates: {
    a: [
      {
        id: 't1',
        name: '文档问答助手',
        enabled: true,
        systemPrompt: '你是文档检索助手，优先依据检索到的资料回答，并注明来源。',
        skills: ['lang-style-guide v3', 'api-conventions v5'],
        mcp: ['s1', 's2'],
      },
      { id: 't2', name: '本地文件分析', enabled: true, systemPrompt: '分析本地目录中的文本资料。', skills: [], mcp: ['s3'] },
      { id: 't3', name: '旧资料检索', enabled: true, systemPrompt: '从旧资料服务检索内容。', skills: [], mcp: ['s4', 's1'] },
    ],
    b: [{ id: 't9', name: '通用助手', enabled: true, systemPrompt: '通用问答。', skills: [], mcp: ['s9'] }],
  },
};

const state = {
  tenant: 'a',
  role: 'edit',
  scenario: 'normal',
  stdio: false,
  view: 'servers',
  editingTemplate: null,
  chatTimers: [],
};

const $ = (id) => document.getElementById(id);
const servers = () => DB.servers[state.tenant];
const templates = () => DB.templates[state.tenant];
const serverById = (id) => servers().find((s) => s.id === id);
const esc = (text) => String(text).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

/* 运行时状态：已停用 / stdio 未启用 / 不可用 / 已连接 / 未连接。 */
function statusOf(s) {
  if (!s.enabled) {
    return { text: '已停用', cls: 'off' };
  }
  if (s.transport === 'stdio' && !state.stdio) {
    return { text: 'stdio 未启用', cls: 'warn' };
  }
  if (s.error) {
    return { text: '不可用 · ' + s.error, cls: 'bad' };
  }
  if (s.connected) {
    return { text: `已连接 · ${s.tools.length} 个工具`, cls: '' };
  }
  return { text: '未连接', cls: 'off' };
}

const TRANSPORTS = {
  'http-streamable': 'Streamable HTTP',
  'http-sse': 'HTTP+SSE',
  stdio: 'stdio',
};
const transportLabel = (s) => TRANSPORTS[s.transport] || s.transport;

let toastTimer = null;
function blocked() {
  if (document.body.classList.contains('readonly')) {
    toast('只读预览：该角色下管理操作不可用');
    return true;
  }
  return false;
}
function toast(text) {
  const el = $('toast');
  el.textContent = text;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.hidden = true;
  }, 2400);
}

/* ---------- 视图切换 ---------- */

const BREADCRUMBS = { servers: '工作区 / MCP 服务', templates: '工作区 / 模板与绑定', chat: '工作区 / 发起对话' };

function switchView(view) {
  state.view = view;
  document.querySelectorAll('.workspace-nav > p[data-view]').forEach((p) => p.classList.toggle('active', p.dataset.view === view));
  document.querySelectorAll('.view').forEach((v) => {
    v.hidden = v.id !== 'view-' + view;
  });
  $('breadcrumb').textContent = BREADCRUMBS[view];
  render();
}

/* ---------- MCP 服务视图 ---------- */

function renderServers() {
  const list = $('server-list');
  $('server-count').textContent = servers().length;
  if (state.scenario === 'error') {
    list.innerHTML =
      '<div class="load-error"><h2>服务清单加载失败</h2><p>GET /api/mcp/servers · 502 BAD_GATEWAY：上游暂时不可用</p><div class="dialog-actions" style="justify-content:center"><button class="secondary" onclick="render()">重试</button></div></div>';
    return;
  }
  if (state.scenario === 'loading') {
    list.innerHTML = '<div class="loading-grid"><div></div><div></div></div>';
    return;
  }
  if (state.scenario === 'empty' || servers().length === 0) {
    list.innerHTML =
      '<div class="empty-state"><div class="empty-mark">⌘</div><h2>还没有 MCP 服务</h2><p>先登记一个远程 MCP Server（如 context7），保存前可以测试连接并预览工具清单；之后在模板里绑定它。</p><div class="actions"><button class="guard" data-new="1">新建服务</button></div></div>';
    return;
  }
  list.innerHTML = '<div class="cards">' + servers().map(serverCard).join('') + '</div>';
}

function serverCard(s) {
  const st = statusOf(s);
  const endpoint = s.transport === 'stdio' ? [s.command, ...(s.args || [])].join(' ') : s.url;
  const endpointLabel = s.transport === 'stdio' ? '命令' : '地址';
  const authNote = s.auth && s.auth.length ? ' · 鉴权头 ' + s.auth.length : '';
  const toolsBtn =
    s.tools.length && st.cls !== 'bad' && st.cls !== 'warn' && s.enabled
      ? `<button class="tool-link" data-tools="${s.id}">${s.tools.length} 个工具</button>`
      : `<button class="tool-link" disabled>${s.tools.length} 个工具</button>`;
  return `<article class="card">
    <div class="card-top"><span class="transport">${esc(transportLabel(s))}</span><span class="status ${st.cls}">${esc(st.text)}</span></div>
    <div class="card-title"><h2>${esc(s.name)}</h2></div>
    <p class="slug">mcp__${esc(s.slug)}__*</p>
    <p class="endpoint" title="${esc(endpoint)}">${endpointLabel} · ${esc(endpoint)}</p>
    <div class="card-bottom">
      <span>${esc(s.updatedAt)}${authNote} · ${toolsBtn}</span>
      <span class="card-actions">
        <button class="guard" data-test="${s.id}">测试连接</button>
        <button class="guard" data-edit="${s.id}">编辑</button>
        <button class="guard" data-toggle="${s.id}">${s.enabled ? '停用' : '启用'}</button>
        <button class="danger guard" data-delete="${s.id}">删除</button>
      </span>
    </div>
  </article>`;
}

/* ---------- 服务编辑对话框 ---------- */

let editingServer = null;
let authDraft = [];

function openServerDialog(id) {
  editingServer = id ? serverById(id) : null;
  const dialog = $('server-dialog');
  $('server-dialog-title').textContent = editingServer ? '编辑服务' : '新建服务';
  $('server-dialog-state').textContent = editingServer ? transportLabel(editingServer) : '新配置';
  $('f-name').value = editingServer ? editingServer.name : '';
  $('f-slug').value = editingServer ? editingServer.slug : '';
  $('f-transport').value = editingServer ? editingServer.transport : 'http-streamable';
  $('f-url').value = editingServer && editingServer.url ? editingServer.url : '';
  $('f-command').value = editingServer && editingServer.command ? editingServer.command : '';
  $('f-args').value = editingServer && editingServer.args ? editingServer.args.join('\n') : '';
  $('f-env').value = editingServer && editingServer.env ? editingServer.env.join('\n') : '';
  authDraft = editingServer ? editingServer.auth.map((row) => ({ ...row })) : [];
  $('form-error').textContent = '';
  $('test-result').hidden = true;
  $('test-result').innerHTML = '';
  syncTransportFields();
  renderAuthRows();
  dialog.showModal();
}

function syncTransportFields() {
  const transport = $('f-transport').value;
  const isHttp = transport === 'http-streamable' || transport === 'http-sse';
  $('http-fields').hidden = !isHttp;
  $('stdio-fields').hidden = transport !== 'stdio';
  $('stdio-hint').hidden = !(transport === 'stdio' && !state.stdio);
  const editingStdio = editingServer && editingServer.transport === 'stdio';
  const stdioOption = [...$('f-transport').options].find((o) => o.value === 'stdio');
  if (stdioOption) {
    stdioOption.disabled = !state.stdio && !editingStdio;
  }
  refreshDropdown($('f-transport'));
}

function renderAuthRows() {
  const box = $('auth-rows');
  box.innerHTML = authDraft
    .map(
      (row, i) => `<div class="kv-row">
      <input class="mono" placeholder="Header 名（如 Authorization）" value="${esc(row.name || '')}" data-auth-name="${i}" />
      <input class="mono" type="password" placeholder="${row.value === null ? '已设置，留空保持不变' : '值'}" data-auth-value="${i}" />
      <button type="button" data-auth-remove="${i}" aria-label="移除">×</button>
    </div>`,
    )
    .join('');
}

function collectAuth() {
  const rows = [];
  const names = document.querySelectorAll('[data-auth-name]');
  names.forEach((el, i) => {
    const name = el.value.trim();
    const valueEl = document.querySelector(`[data-auth-value="${i}"]`);
    if (!name) {
      return;
    }
    const typed = valueEl.value;
    const prev = authDraft[i];
    let value;
    if (typed !== '') {
      value = typed;
    } else if (prev && prev.value === null) {
      value = null;
    } else {
      value = prev ? prev.value : '';
    }
    rows.push({ name, value: value === '' ? '' : value === null ? null : '已设置' });
  });
  return rows;
}

function validateServerForm() {
  const name = $('f-name').value.trim();
  const slug = $('f-slug').value.trim();
  const transport = $('f-transport').value;
  if (!name) {
    return '名称必填';
  }
  if (!/^[a-z][a-z0-9-]{1,31}$/.test(slug)) {
    return 'slug 需为 2-32 位小写字母、数字、连字符，且以字母开头';
  }
  if (servers().some((s) => s.slug === slug && s !== editingServer)) {
    return 'slug 已被当前租户的其他服务使用';
  }
  if (transport === 'stdio' && !state.stdio) {
    return 'stdio 总开关未启用（loopcraft.mcp.stdio.enabled=false），不能保存 stdio 服务';
  }
  if (transport.startsWith('http') && !/^https?:\/\/.+/.test($('f-url').value.trim())) {
    return '远程服务地址必须以 http(s):// 开头';
  }
  if (transport === 'stdio' && !$('f-command').value.trim()) {
    return 'stdio 命令必填';
  }
  return '';
}

function applyServerForm() {
  const error = validateServerForm();
  if (error) {
    $('form-error').textContent = error;
    return false;
  }
  const transport = $('f-transport').value;
  const record = {
    name: $('f-name').value.trim(),
    slug: $('f-slug').value.trim(),
    transport,
    updatedAt: new Date().toLocaleString('zh-CN', { hour12: false }).replace(/\//g, '-'),
  };
  if (transport.startsWith('http')) {
    record.url = $('f-url').value.trim();
    record.auth = collectAuth();
  } else {
    record.command = $('f-command').value.trim();
    record.args = $('f-args').value.split('\n').map((v) => v.trim()).filter(Boolean);
    record.env = $('f-env').value.split('\n').map((v) => v.trim()).filter(Boolean);
  }
  const tested = $('test-result').dataset.tested === 'ok';
  if (editingServer) {
    const endpointChanged =
      editingServer.transport !== record.transport
      || (record.url !== undefined && editingServer.url !== record.url)
      || (record.command !== undefined && editingServer.command !== record.command);
    Object.assign(editingServer, record);
    if (tested) {
      editingServer.connected = true;
      editingServer.error = null;
    } else if (endpointChanged) {
      editingServer.connected = false;
      editingServer.error = null;
      editingServer.negotiated = null;
    }
    toast('服务已保存');
  } else {
    servers().push({
      id: 's' + Math.random().toString(36).slice(2, 8),
      enabled: true,
      connected: tested,
      error: null,
      tools: tested ? mockTools() : [],
      ...record,
    });
    toast(tested ? '服务已保存并完成一次连接测试' : '服务已保存（未测试连接）');
  }
  render();
  return true;
}

function mockTools() {
  return [
    { name: 'search', desc: '检索资源（新服务示例）', params: '{ "query": string }' },
    { name: 'fetch', desc: '按标识获取资源（新服务示例）', params: '{ "id": string }' },
  ];
}

function testConnection() {
  const error = validateServerForm();
  if (error) {
    $('form-error').textContent = error;
    return;
  }
  const button = $('test-conn');
  const transport = $('f-transport').value;
  const url = $('f-url').value.trim();
  button.disabled = true;
  button.textContent = '连接中…';
  setTimeout(() => {
    button.disabled = false;
    button.textContent = '测试连接';
    const box = $('test-result');
    box.hidden = false;
    const fail = (transport === 'stdio' && !state.stdio) || url.includes('example.com/mcp');
    if (fail) {
      delete box.dataset.tested;
      const reason = transport === 'stdio' ? 'stdio 总开关未启用，不能启动子进程' : 'initialize 握手超时（5s），请检查地址与出站网络';
      box.innerHTML = `<div class="test-bad"><b>连接失败</b><p style="margin:6px 0 0">${esc(reason)}</p></div>`;
      return;
    }
    box.dataset.tested = 'ok';
    const negotiated = TRANSPORTS[transport] || transport;
    const tools = editingServer && editingServer.tools.length ? editingServer.tools : mockTools();
    const slug = $('f-slug').value.trim();
    box.innerHTML = `<div class="test-ok"><b>连接成功</b>
      <div class="test-meta"><span class="badge">协议 2025-06-18</span><span class="badge">传输 ${negotiated}</span><span class="badge">握手 412ms</span></div>
      <ul class="test-tools">${tools.map((t) => `<li>mcp__${esc(slug)}__${esc(t.name)}</li>`).join('')}</ul>
      <p class="hint" style="color:var(--secondary)">测试连接不改变连接池状态；工具清单在运行受理时按连接缓存解析。</p>
    </div>`;
  }, 700);
}

/* ---------- 工具清单对话框 ---------- */

function openToolsDialog(id) {
  const s = serverById(id);
  if (!s) {
    return;
  }
  $('tools-dialog-title').textContent = s.name + ' · 工具清单';
  $('tools-dialog-meta').textContent = transportLabel(s);
  $('tools-list').innerHTML = s.tools
    .map(
      (t) => `<div class="tool-row">
      <span class="mono">mcp__${esc(s.slug)}__${esc(t.name)}</span>
      <p>${esc(t.desc)}</p>
      <p class="hint mono">${esc(t.params)}</p>
    </div>`,
    )
    .join('');
  $('tools-dialog').showModal();
}

/* ---------- 模板视图 ---------- */

function renderTemplates() {
  const body = $('template-body');
  $('back-templates').hidden = !state.editingTemplate;
  if (!state.editingTemplate) {
    body.innerHTML =
      '<div class="template-list">' +
      templates()
        .map((t) => {
          const bound = t.mcp.map((id) => serverById(id)).filter(Boolean);
          const summary = `技能 ${t.skills.length} · MCP 服务 ${bound.length}${bound.length ? '（' + bound.map((s) => s.slug).join('、') + '）' : ''}`;
          return `<div class="template-row guard" data-template="${t.id}" role="button" tabindex="0">
        <div><h2>${esc(t.name)}</h2><p class="muted">${esc(summary)} · ${t.enabled ? '启用中' : '已停用'}</p></div>
        <span class="muted">编辑绑定 →</span>
      </div>`;
        })
        .join('') +
      '</div>';
    return;
  }
  renderTemplateEditor();
}

function renderTemplateEditor() {
  const t = templates().find((row) => row.id === state.editingTemplate);
  const body = $('template-body');
  const bound = t.mcp.map((id) => serverById(id)).filter(Boolean);
  const unbound = servers().filter((s) => !t.mcp.includes(s.id));
  body.innerHTML = `<div class="editor-layout">
    <div class="editor-stack">
      <div class="panel">
        <div class="section-heading"><h2>基本信息</h2><span class="badge">${t.enabled ? '启用中' : '已停用'}</span></div>
        <div class="form-grid">
          <label>名称<input id="t-name" value="${esc(t.name)}" /></label>
          <label class="full">系统提示<textarea id="t-prompt" rows="3">${esc(t.systemPrompt)}</textarea></label>
        </div>
      </div>
      <div class="panel">
        <div class="section-heading"><h2>MCP 服务绑定</h2><span class="badge">按顺序解析</span></div>
        ${t.mcp.length === 0 ? '<p class="hint">尚未绑定服务。发起对话时仅有内置工具。</p>' : ''}
        ${bound
          .map((s, i) => {
            const st = statusOf(s);
            const warn = st.cls ? `<p class="bind-warning">受理将被拒绝：${esc(st.text)}</p>` : '';
            return `${i > 0 && st.cls ? '' : ''}<div class="bind-row">
          <div><b>${esc(s.name)}</b><div class="mono">mcp__${esc(s.slug)}__*</div></div>
          <div class="bind-actions">
            <button class="guard" data-up="${s.id}" ${i === 0 ? 'disabled' : ''}>↑</button>
            <button class="guard" data-down="${s.id}" ${i === bound.length - 1 ? 'disabled' : ''}>↓</button>
            <button class="guard" data-unbind="${s.id}">移除</button>
          </div>
        </div>${warn}`;
          })
          .join('')}
        ${
          unbound.length
            ? `<div class="bind-add"><select id="bind-select">${unbound
                .map((s) => `<option value="${s.id}">${esc(s.name)}（mcp__${esc(s.slug)}__*）</option>`)
                .join('')}</select><button class="secondary guard" id="bind-add-btn">添加</button></div>`
            : '<p class="hint" style="margin-top:12px">当前租户没有更多可绑定的服务。</p>'
        }
      </div>
    </div>
    <div>
      <div class="panel">
        <div class="section-heading"><h2>运行时工具预览</h2></div>
        <ul class="runtime-list">
          <li><span class="mono">list_skills</span><span class="muted">内置</span></li>
          <li><span class="mono">read_skill</span><span class="muted">内置</span></li>
          ${runtimeTools(bound)}
        </ul>
        <p class="hint" style="margin-top:16px">白名单在受理时解析为固定快照，运行期不随服务端工具变化。</p>
      </div>
      <div class="dialog-actions"><button class="guard" id="t-save">保存模板</button></div>
    </div>
  </div>`;
  $('t-save').addEventListener('click', () => {
    if (blocked()) {
      return;
    }
    t.name = $('t-name').value.trim() || t.name;
    t.systemPrompt = $('t-prompt').value;
    toast('模板已保存');
    render();
  });
  $('bind-add-btn').addEventListener('click', () => {
    if (blocked()) {
      return;
    }
    const id = $('bind-select').value;
    if (id) {
      t.mcp.push(id);
      renderTemplateEditor();
      enhanceSelects(document);
    }
  });
}

function runtimeTools(bound) {
  return bound
    .map((s) => {
      const st = statusOf(s);
      if (st.cls) {
        return `<li class="unavailable"><span class="mono">mcp__${esc(s.slug)}__*</span><span>不可用 · ${esc(st.text)}</span></li>`;
      }
      return s.tools.length
        ? s.tools
            .map(
              (tool) =>
                `<li><span class="mono">mcp__${esc(s.slug)}__${esc(tool.name)}</span><span class="muted">${esc(s.name)}</span></li>`,
            )
            .join('')
        : `<li class="unavailable"><span class="mono">mcp__${esc(s.slug)}__*</span><span>暂未发现工具</span></li>`;
    })
    .join('');
}

/* ---------- 发起对话模拟 ---------- */

function renderChat() {
  const select = $('chat-template');
  select.innerHTML = templates()
    .map((t) => {
      const bound = t.mcp.map((id) => serverById(id)).filter(Boolean);
      const bad = bound.filter((s) => statusOf(s).cls).length;
      const flag = bad ? `（${bad} 个服务异常）` : '';
      return `<option value="${t.id}">${esc(t.name)}${flag}</option>`;
    })
    .join('');
}

function sendChat() {
  state.chatTimers.forEach(clearTimeout);
  state.chatTimers = [];
  const t = templates().find((row) => row.id === $('chat-template').value);
  const box = $('chat-result');
  const blockers = t.mcp
    .map((id) => serverById(id))
    .filter(Boolean)
    .map((s) => ({ s, st: statusOf(s) }))
    .filter((row) => row.st.cls);
  if (blockers.length) {
    box.innerHTML = `<div class="result-card rejected">
      <h2>拒绝受理</h2>
      <p class="meta">HTTP 503 SERVICE_UNAVAILABLE · ${esc(t.name)}</p>
      <ul class="reject-list">${blockers
        .map((row) => `<li>${esc(row.s.name)}（mcp__${esc(row.s.slug)}__*）：${esc(row.st.text)}</li>`)
        .join('')}</ul>
      <p class="hint" style="color:var(--secondary)">按快速失败语义：任一绑定服务不可用即整次拒绝，不降级运行；修复后重新发起。</p>
    </div>`;
    return;
  }
  const runId = crypto.randomUUID();
  const tools = [
    ...new Set(
      t.mcp.flatMap((id) => {
        const s = serverById(id);
        if (!s || !s.tools.length) {
          return [];
        }
        return s.tools.map((tool) => `mcp__${s.slug}__${tool.name}`);
      }),
    ),
  ].slice(0, 3);
  const steps = [
    { label: 'iteration_started', detail: '{"iteration":1,"maxIterations":16}' },
    ...tools.flatMap((name, i) => [
      { label: 'tool_called', detail: name },
      { label: 'tool_finished', detail: 'ok · ' + [842, 1930, 610][i % 3] + 'ms', cls: 'ok' },
    ]),
    { label: 'run_finished', detail: 'COMPLETED · ' + (1 + tools.length) + ' tool calls', cls: 'ok' },
  ];
  box.innerHTML = `<div class="result-card accepted">
    <h2>已受理，进入循环</h2>
    <p class="meta">HTTP 202 ACCEPTED · runId ${esc(runId)}</p>
    <ul class="timeline" id="chat-timeline"></ul>
  </div>`;
  const ul = $('chat-timeline');
  let i = 0;
  const advance = () => {
    if (i >= steps.length) {
      return;
    }
    const step = steps[i++];
    if (i > 1) {
      ul.lastElementChild.className = steps[i - 2].cls || '';
    }
    const li = document.createElement('li');
    li.className = 'pending';
    li.textContent = step.label + '  ' + step.detail;
    ul.appendChild(li);
    state.chatTimers.push(setTimeout(advance, 750));
  };
  advance();
}

/* ---------- 框架 Select 形态下拉：原生 select 隐藏为状态载体，逻辑仍读写其值 ---------- */

const CHEVRON =
  '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>';
const CHECK =
  '<svg class="check" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M20 6 9 17l-5-5"/></svg>';

function selectedOption(sel) {
  return [...sel.options].find((o) => o.value === sel.value) || sel.options[0];
}

function drawTrigger(sel, wrap) {
  let trigger = wrap.querySelector('.dropdown-trigger');
  if (!trigger) {
    trigger = document.createElement('button');
    trigger.type = 'button';
    trigger.className = 'dropdown-trigger';
    trigger.setAttribute('aria-haspopup', 'listbox');
    wrap.appendChild(trigger);
  }
  const option = selectedOption(sel);
  trigger.innerHTML = '<span>' + esc(option ? option.textContent : '') + '</span>' + CHEVRON;
}

function drawMenu(sel, wrap) {
  let menu = wrap.querySelector('.dropdown-menu');
  if (!menu) {
    menu = document.createElement('div');
    menu.className = 'dropdown-menu';
    menu.setAttribute('role', 'listbox');
    wrap.appendChild(menu);
  }
  menu.innerHTML = [...sel.options]
    .map(
      (o) =>
        '<button type="button" class="dropdown-option' +
        (o.value === sel.value ? ' selected' : '') +
        '" role="option" data-value="' +
        esc(o.value) +
        '"' +
        (o.disabled ? ' disabled' : '') +
        '><span>' +
        esc(o.textContent) +
        '</span>' +
        CHECK +
        '</button>',
    )
    .join('');
  menu.hidden = false;
}

function closeDropdowns() {
  document.querySelectorAll('.dropdown-menu').forEach((menu) => {
    menu.hidden = true;
  });
}

function refreshDropdown(sel) {
  const wrap = sel.nextElementSibling;
  if (wrap && wrap.classList.contains('dropdown')) {
    drawTrigger(sel, wrap);
  }
}

function enhanceSelects(root) {
  root.querySelectorAll('select').forEach((sel) => {
    if (sel.dataset.enhanced) {
      refreshDropdown(sel);
      return;
    }
    sel.dataset.enhanced = '1';
    sel.hidden = true;
    const wrap = document.createElement('div');
    wrap.className = 'dropdown';
    sel.insertAdjacentElement('afterend', wrap);
    drawTrigger(sel, wrap);
    wrap.addEventListener('click', (event) => {
      if (event.target.closest('.dropdown-trigger')) {
        const menu = wrap.querySelector('.dropdown-menu');
        const wasOpen = menu && !menu.hidden;
        closeDropdowns();
        if (!wasOpen) {
          drawMenu(sel, wrap);
        }
        return;
      }
      const option = event.target.closest('.dropdown-option');
      if (option && !option.disabled) {
        sel.value = option.dataset.value;
        sel.dispatchEvent(new Event('change', { bubbles: true }));
        closeDropdowns();
        drawTrigger(sel, wrap);
      }
    });
  });
}

/* ---------- 渲染入口与事件 ---------- */

function render() {
  renderServers();
  renderTemplates();
  renderChat();
  enhanceSelects(document);
}

document.addEventListener('DOMContentLoaded', () => {
  document.querySelectorAll('.workspace-nav > p[data-view]').forEach((p) => {
    p.addEventListener('click', () => switchView(p.dataset.view));
  });
  $('tenant').addEventListener('change', (e) => {
    state.tenant = e.target.value;
    state.editingTemplate = null;
    render();
  });
  $('role').addEventListener('change', (e) => {
    state.role = e.target.value;
    document.body.classList.toggle('readonly', state.role === 'read');
    render();
  });
  $('scenario').addEventListener('change', (e) => {
    state.scenario = e.target.value;
    renderServers();
  });
  $('stdio').addEventListener('change', (e) => {
    state.stdio = e.target.checked;
    render();
  });
  $('back-templates').addEventListener('click', () => {
    state.editingTemplate = null;
    renderTemplates();
  });
  $('chat-send').addEventListener('click', sendChat);

  document.addEventListener('click', (event) => {
    const guard = event.target.closest('.guard');
    if (guard && document.body.classList.contains('readonly')) {
      event.preventDefault();
      event.stopPropagation();
      toast('只读预览：该角色下管理操作不可用');
      return;
    }
    const target = event.target.closest('button, .template-row');
    if (!target) {
      return;
    }
    if (target.dataset.new) {
      openServerDialog(null);
    } else if (target.dataset.tools) {
      openToolsDialog(target.dataset.tools);
    } else if (target.dataset.edit) {
      openServerDialog(target.dataset.edit);
    } else if (target.dataset.test) {
      const s = serverById(target.dataset.test);
      toast('正在测试 ' + s.name + ' …');
      setTimeout(() => {
        if (s.error || (s.transport === 'stdio' && !state.stdio)) {
          toast('连接失败：' + (s.error || 'stdio 未启用'));
        } else {
          s.connected = true;
          toast('连接成功 · ' + s.tools.length + ' 个工具（协议 2025-06-18）');
          renderServers();
        }
      }, 700);
    } else if (target.dataset.toggle) {
      const s = serverById(target.dataset.toggle);
      s.enabled = !s.enabled;
      toast(s.enabled ? '已启用' : '已停用');
      render();
    } else if (target.dataset.delete) {
      const s = serverById(target.dataset.delete);
      const boundTemplates = templates().filter((t) => t.mcp.includes(s.id));
      if (boundTemplates.length) {
        openConfirm(
          '无法删除服务',
          `以下模板仍在绑定「${s.name}」，请先在模板中解绑：${boundTemplates.map((t) => t.name).join('、')}。`,
          null,
        );
      } else {
        openConfirm('删除服务', `将删除「${s.name}」。没有模板绑定该服务。`, () => {
          servers().splice(servers().indexOf(s), 1);
          toast('服务已删除');
          render();
        });
      }
    } else if (target.dataset.template) {
      state.editingTemplate = target.dataset.template;
      renderTemplates();
    } else if (target.dataset.up || target.dataset.down) {
      const t = templates().find((row) => row.id === state.editingTemplate);
      const id = target.dataset.up || target.dataset.down;
      const delta = target.dataset.up ? -1 : 1;
      const index = t.mcp.indexOf(id);
      [t.mcp[index], t.mcp[index + delta]] = [t.mcp[index + delta], t.mcp[index]];
      renderTemplateEditor();
    } else if (target.dataset.unbind) {
      const t = templates().find((row) => row.id === state.editingTemplate);
      t.mcp = t.mcp.filter((id) => id !== target.dataset.unbind);
      renderTemplateEditor();
    }
  });

  $('f-transport').addEventListener('change', syncTransportFields);
  $('add-auth').addEventListener('click', () => {
    authDraft.push({ name: '', value: '' });
    renderAuthRows();
    const inputs = document.querySelectorAll('[data-auth-name]');
    inputs[inputs.length - 1].focus();
  });
  $('auth-rows').addEventListener('click', (event) => {
    const remove = event.target.closest('[data-auth-remove]');
    if (remove) {
      authDraft.splice(Number(remove.dataset.authRemove), 1);
      renderAuthRows();
    }
  });
  $('test-conn').addEventListener('click', testConnection);
  $('server-form').addEventListener('submit', (event) => {
    const submitter = event.submitter;
    if (submitter && submitter.value === 'cancel') {
      return;
    }
    event.preventDefault();
    if (applyServerForm()) {
      $('server-dialog').close();
    }
  });
  $('tools-close').addEventListener('click', () => $('tools-dialog').close());
  document.addEventListener('click', (event) => {
    if (!event.target.closest('.dropdown')) {
      closeDropdowns();
    }
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') {
      closeDropdowns();
    }
  });
  switchView('servers');
});

/* 确认对话框 */
let confirmAction = null;
function openConfirm(title, text, onYes) {
  $('confirm-title').textContent = title;
  $('confirm-text').textContent = text;
  confirmAction = onYes;
  $('confirm-yes').hidden = !onYes;
  $('confirm-dialog').showModal();
}
document.addEventListener('DOMContentLoaded', () => {
  $('confirm-no').addEventListener('click', () => $('confirm-dialog').close());
  $('confirm-yes').addEventListener('click', () => {
    $('confirm-dialog').close();
    if (confirmAction) {
      confirmAction();
    }
  });
});
