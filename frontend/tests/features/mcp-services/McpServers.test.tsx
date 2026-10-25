import { response } from '../../fixtures/http'
import { createDom } from '../../support/dom'
import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { act } from 'react'
import type { Root } from 'react-dom/client'
import { McpServers } from '@/features/mcp-services/McpServers'

let dispose: () => Promise<void>
let container: HTMLDivElement
let root: Root
let probeCalls = 0

const httpServer = {
  id: 1,
  name: 'Context7 检索',
  slug: 'context7',
  transport: 'HTTP_STREAMABLE',
  url: 'https://mcp.example.com/mcp',
  authHeaders: [{ name: 'Authorization' }],
  command: null,
  args: [],
  envVars: [],
  toolTimeoutMs: 30000,
  enabled: true,
  createdAt: '',
  updatedAt: '',
}
const stdioServer = {
  ...httpServer,
  id: 2,
  name: '本地文件',
  slug: 'filesystem',
  transport: 'STDIO',
  url: null,
  command: 'npx',
  args: ['server-filesystem', '/data'],
  enabled: true,
}

beforeEach(async () => {
  const dom = await createDom()
  container = dom.container
  root = dom.root
  dispose = dom.dispose
  probeCalls = 0
  globalThis.fetch = async (url) => {
    const path = String(url)
    if (path === '/api/auth/csrf')
      return response({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' })
    if (path.includes('/options')) return response({ stdioEnabled: false })
    if (path.endsWith('/mcp-servers/1/test')) {
      probeCalls += 1
      return response({
        ok: false,
        reason: '握手超时（5s）',
        protocolVersion: null,
        serverName: null,
        serverVersion: null,
        transport: null,
        tools: [],
      })
    }
    const params = new URL(path, 'http://test').searchParams
    return response({
      records: params.get('q') ? [] : [httpServer, stdioServer],
      total: params.get('q') ? 0 : 2,
      page: 1,
      size: 20,
    })
  }
})
afterEach(async () => {
  await dispose()
})

async function render(canManage: boolean) {
  await act(async () => root.render(<McpServers tenantId={1} canManage={canManage} />))
}

test('成员只读：展示清单与 stdio 未启用提示，不出现管理操作', async () => {
  await render(false)
  assert.equal(container.textContent?.includes('Context7 检索'), true)
  assert.equal(container.textContent?.includes('本地文件'), true)
  assert.equal(container.textContent?.includes('stdio 未启用'), true)
  assert.equal(container.textContent?.includes('配置由负责人管理'), true)
  assert.equal(container.querySelector('button')?.textContent?.includes('新建服务'), false)
  assert.equal(
    [...container.querySelectorAll('button')].some((button) => button.textContent === '测试连接'),
    false,
  )
})

test('管理员可打开新建表单：远程字段与未启用 stdio 选项提示可见', async () => {
  await render(true)
  await act(async () => {
    const create = [...container.querySelectorAll('button')].find(
      (button) => button.textContent === '新建服务',
    )
    create?.click()
  })
  // Radix 弹层挂载在 document.body，不在渲染容器内。
  assert.equal(document.body.textContent?.includes('新建 MCP 服务'), true)
  assert.equal(document.body.textContent?.includes('服务地址'), true)
  assert.equal(document.body.textContent?.includes('鉴权头'), true)
  assert.equal(
    document.body.textContent?.includes(
      'stdio 总开关未启用（loopcraft.mcp.stdio.enabled=false），不能保存 stdio 服务',
    ),
    false,
  )
  const editor = [...document.body.querySelectorAll('button')].find(
    (button) => button.textContent === '测试连接',
  )
  assert.notEqual(editor, undefined)
})

test('卡片测试连接展示失败原因对话框', async () => {
  await render(true)
  await act(async () => {
    const test = [...container.querySelectorAll('button')].filter(
      (button) => button.textContent === '测试连接',
    )[0]
    test?.click()
  })
  assert.equal(probeCalls, 1)
  assert.equal(document.body.textContent?.includes('连接测试 · Context7 检索'), true)
  assert.equal(document.body.textContent?.includes('连接失败：握手超时（5s）'), true)
})
