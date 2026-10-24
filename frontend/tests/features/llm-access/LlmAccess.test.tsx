import { response } from '../../fixtures/http'
import { createDom } from '../../support/dom'
import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { act } from 'react'
import type { Root } from 'react-dom/client'
import { LlmAccess } from '@/features/llm-access/LlmAccess'

let dispose: () => Promise<void>
let container: HTMLDivElement
let root: Root
let deleted = false
let detailFailure = false
const source = {
  id: 1,
  name: '待检查连接',
  supplierName: '测试供应商',
  protocol: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://example.test/v1',
  hasApiKey: false,
  enabled: true,
  modelCount: 0,
  updatedAt: '',
}
beforeEach(async () => {
  const dom = await createDom()
  container = dom.container
  root = dom.root
  dispose = dom.dispose
  deleted = false
  detailFailure = false
  globalThis.fetch = async (url) => {
    const path = String(url)
    if (path.endsWith('/llm-sources/1')) {
      if (detailFailure)
        return Response.json({ code: 500, message: '暂时失败', data: null }, { status: 500 })
      return deleted
        ? Response.json({ code: 404, message: '连接不存在', data: null }, { status: 404 })
        : response(source)
    }
    if (path.includes('/models?')) return response({ records: [], total: 0, page: 1, size: 20 })
    // 筛选和翻页可隐藏仍存在的选中项，不能把不在当前页等同于已删除。
    const params = new URL(path, 'http://test').searchParams
    return response({
      records: deleted || params.get('q') ? [] : [source],
      total: deleted ? 0 : 1,
      page: 1,
      size: 20,
    })
  }
})
afterEach(async () => {
  await dispose()
})
async function render() {
  await act(async () => root.render(<LlmAccess tenantId={1} canManage={false} />))
  assert.ok(container.querySelector('[aria-label="连接详情"]'))
}
async function refresh() {
  const button = container.querySelector('[aria-label="刷新连接"]')
  assert.ok(button instanceof HTMLButtonElement)
  await act(async () => button.click())
}

test('其他会话删除选中连接后，刷新清除详情和模型面板', async () => {
  await render()
  deleted = true
  await refresh()
  // 只把布尔值交给断言，避免失败时展开包含循环引用的 DOM 对象。
  assert.ok(
    container.querySelector('[aria-label="连接详情"]') === null,
    '删除连接后，详情和模型面板应消失',
  )
  assert.match(container.textContent ?? '', /选中的连接已删除/)
  assert.match(container.textContent ?? '', /选择一个连接以查看模型/)
})

test('筛选隐藏的现存连接保留详情，临时请求失败也不当作删除', async () => {
  await render()
  const input = container.querySelector('[aria-label="搜索连接名称"]')
  assert.ok(input instanceof HTMLInputElement)
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(
      input,
      '其他连接',
    )
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
  // 搜索输入有防抖，等待防抖窗口过后列表按关键词重新请求。
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 320))
  })
  assert.match(container.textContent ?? '', /没有匹配的连接/)
  assert.ok(container.querySelector('[aria-label="连接详情"]'))
  detailFailure = true
  await refresh()
  assert.ok(container.querySelector('[aria-label="连接详情"]'))
  assert.match(container.textContent ?? '', /暂时失败/)
})

test('旧连接迟到的 404 不会清除后来选择的连接', async () => {
  await render()
  const fallback = globalThis.fetch
  const other = { ...source, id: 2, name: '第二连接' }
  const pending = Promise.withResolvers<Response>()
  globalThis.fetch = async (url, options) => {
    const path = String(url)
    if (path.endsWith('/llm-sources/1')) return pending.promise
    if (path.endsWith('/llm-sources/2')) return response(other)
    if (path.includes('/llm-sources?'))
      return response({ records: [source, other], total: 2, page: 1, size: 20 })
    return fallback(url, options)
  }
  await refresh()
  const button = Array.from(container.querySelectorAll('button')).find((item) =>
    item.textContent?.includes('第二连接'),
  )
  assert.ok(button)
  await act(async () => button.click())
  await act(async () => {
    pending.resolve(
      Response.json({ code: 404, message: '连接不存在', data: null }, { status: 404 }),
    )
  })
  const details = container.querySelector('[aria-label="连接详情"]')
  assert.match(details?.textContent ?? '', /第二连接/)
  assert.doesNotMatch(container.textContent ?? '', /选中的连接已删除/)
})
