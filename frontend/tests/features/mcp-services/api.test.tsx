import { response } from '../../fixtures/http'
import { afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { clearCsrf } from '@/lib/api'
import { isServer, mcpApi } from '@/features/mcp-services/api'

const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
  clearCsrf()
})

const server = {
  id: 1,
  name: 'Context7',
  slug: 'context7',
  transport: 'HTTP_STREAMABLE',
  url: 'https://mcp.example.com/mcp',
  authHeaders: [{ name: 'Authorization' }],
  command: null,
  args: [],
  envVars: [],
  toolTimeoutMs: null,
  enabled: true,
  createdAt: '',
  updatedAt: '',
}

test('接口边界拒绝未知传输、空标识与坏鉴权头清单', () => {
  assert.equal(isServer(server), true)
  assert.equal(isServer({ ...server, transport: 'HTTP' }), false)
  assert.equal(isServer({ ...server, transport: null }), false)
  assert.equal(isServer({ ...server, url: null }), true)
  assert.equal(isServer({ ...server, authHeaders: 'Authorization' }), false)
  assert.equal(isServer({ ...server, toolTimeoutMs: -1 }), false)
  assert.equal(isServer({ ...server, enabled: null }), false)
})

test('写入与探测请求使用正确的路径、方法与负载', async () => {
  const calls: {
    path: string
    method: string
    body: { authHeaders?: { value?: string }[]; transport?: string } | null
  }[] = []
  globalThis.fetch = async (url, options) => {
    const path = String(url)
    const method = String(options?.method ?? 'GET')
    if (path === '/api/auth/csrf')
      return response({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' })
    calls.push({
      path,
      method,
      body: options?.body ? JSON.parse(String(options.body)) : null,
    })
    if (method === 'DELETE') return response(null)
    if (path.includes('/options')) return response({ stdioEnabled: false })
    if (path.endsWith('/test')) {
      return response({
        ok: false,
        reason: '握手超时',
        protocolVersion: null,
        serverName: null,
        serverVersion: null,
        transport: null,
        tools: [],
      })
    }
    return response(server)
  }
  await mcpApi.options(1)
  await mcpApi.create(1, {
    name: 'Context7',
    slug: 'context7',
    transport: 'HTTP_STREAMABLE',
    url: 'https://mcp.example.com/mcp',
    authHeaders: [{ name: 'Authorization', value: 'secret' }],
  })
  await mcpApi.update(1, server.id, { enabled: false })
  await mcpApi.delete(1, server.id)
  await mcpApi.test(1, { name: 'x', slug: 'x', transport: 'HTTP_SSE', url: 'https://e/sse' })
  await mcpApi.testSaved(1, server.id)
  assert.deepEqual(
    calls.map((call) => `${call.method} ${call.path}`),
    [
      'GET /api/tenants/1/mcp-servers/options',
      'POST /api/tenants/1/mcp-servers',
      'PATCH /api/tenants/1/mcp-servers/1',
      'DELETE /api/tenants/1/mcp-servers/1',
      'POST /api/tenants/1/mcp-servers/test',
      'POST /api/tenants/1/mcp-servers/1/test',
    ],
  )
  const createCall = calls[1]
  assert.equal(createCall.body?.authHeaders?.[0]?.value, 'secret')
  const testCall = calls[4]
  assert.equal(testCall.body?.transport, 'HTTP_SSE')
})
