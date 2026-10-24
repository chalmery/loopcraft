import { response } from '../../fixtures/http'
import { afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { renderToStaticMarkup } from 'react-dom/server'
import { clearCsrf } from '@/lib/api'
import { llmApi, isModel, isSource } from '@/features/llm-access/api'
import { LlmAccess } from '@/features/llm-access/LlmAccess'

const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
  clearCsrf()
})
const model = {
  contextLimit: null,
  temperatureSupported: null,
  temperatureMin: null,
  temperatureMax: null,
  id: 1,
  sourceId: 2,
  name: '测试',
  modelId: 'Model-X',
  origin: 'MANUAL',
  enabled: false,
  updatedAt: '',
}
const source = {
  id: 2,
  name: '连接',
  supplierName: '供应商',
  protocol: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://example.test/v1',
  hasApiKey: true,
  enabled: true,
  modelCount: 1,
  updatedAt: '',
}

test('接口边界拒绝无效协议、来源、状态及分页数据', async () => {
  assert.equal(isSource(source), true)
  assert.equal(isSource({ ...source, protocol: 'unknown' }), false)
  assert.equal(isSource({ ...source, hasApiKey: 'yes' }), false)
  assert.equal(isModel({ ...model, sourceId: null }), false)
  assert.equal(isModel({ ...model, enabled: null }), false)
  globalThis.fetch = async () => response({ records: [source], total: -1, page: 1, size: 20 })
  await assert.rejects(llmApi.sources(1, 1, ''))
})

test('查询供应商仅发送已保存连接路径和游标，勾选导入与查询分开', async () => {
  const calls: { path: string; method: string; body: unknown }[] = []
  globalThis.fetch = async (url, options) => {
    if (url === '/api/auth/csrf')
      return response({ headerName: 'X-CSRF-TOKEN', token: 'test-csrf' })
    calls.push({
      path: String(url),
      method: String(options?.method),
      body: JSON.parse(String(options?.body)),
    })
    return String(url).endsWith('/model-discovery')
      ? response({
          records: [{ name: '候选', modelId: 'Model-X' }],
          nextCursor: null,
          supported: true,
        })
      : response({ created: [model], skippedModelIds: [] })
  }
  await llmApi.discover(7, 2, 'next-cursor')
  assert.deepEqual(calls, [
    {
      path: '/api/tenants/7/llm-sources/2/model-discovery',
      method: 'POST',
      body: { cursor: 'next-cursor', limit: 100 },
    },
  ])
  await llmApi.importModels(7, 2, [{ name: '选中', modelId: 'Model-X' }])
  assert.deepEqual(calls[1].body, { models: [{ name: '选中', modelId: 'Model-X' }] })
})

test('候选已有状态读取所有模型页，后续页失败不得视为完整结果', async () => {
  const pages: number[] = []
  globalThis.fetch = async (url) => {
    const page = Number(new URL(String(url), 'http://test').searchParams.get('page'))
    pages.push(page)
    return response({
      records:
        page === 1
          ? Array.from({ length: 100 }, (_, i) => ({ ...model, id: i + 1, modelId: `id-${i}` }))
          : [{ ...model, id: 101, modelId: 'last-disabled' }],
      page,
      size: 100,
      total: 101,
    })
  }
  const ids = await llmApi.existingIds(7, 2)
  assert.equal(ids.size, 101)
  assert.equal(ids.has('last-disabled'), true)
  assert.deepEqual(pages, [1, 2])
  globalThis.fetch = async (url) => {
    if (String(url).includes('page=2')) throw new Error('末页失败')
    return response({
      records: Array.from({ length: 100 }, (_, i) => ({ ...model, id: i + 1 })),
      page: 1,
      size: 100,
      total: 101,
    })
  }
  await assert.rejects(llmApi.existingIds(7, 2), /末页失败/)
})

test('普通成员不显示新增入口，负责人显示入口', () => {
  const member = renderToStaticMarkup(<LlmAccess tenantId={1} canManage={false} />)
  assert.match(member, /配置由负责人管理/)
  assert.doesNotMatch(member, /新增连接<\/button>/)
  const owner = renderToStaticMarkup(<LlmAccess tenantId={1} canManage />)
  assert.match(owner, /新增连接<\/button>/)
})
