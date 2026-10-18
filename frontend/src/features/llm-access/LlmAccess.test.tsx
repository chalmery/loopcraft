import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { Window } from 'happy-dom'
import { act } from 'react'
import type { Root } from 'react-dom/client'
import { LlmAccess } from './LlmAccess'

const originalFetch = globalThis.fetch
const originalGlobals = new Map<string, PropertyDescriptor | undefined>()
let window: Window
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
function response(data: unknown) {
  return Response.json({ code: 0, message: 'success', data })
}
function install(name: string, value: unknown) {
  originalGlobals.set(name, Object.getOwnPropertyDescriptor(globalThis, name))
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
}
beforeEach(async () => {
  window = new Window()
  for (const [name, value] of Object.entries({
    window,
    document: window.document,
    HTMLElement: window.HTMLElement,
    HTMLButtonElement: window.HTMLButtonElement,
    HTMLInputElement: window.HTMLInputElement,
    Event: window.Event,
    Element: window.Element,
    Node: window.Node,
    IS_REACT_ACT_ENVIRONMENT: true,
  }))
    install(name, value)
  container = document.createElement('div')
  document.body.append(container)
  const { createRoot } = await import('react-dom/client')
  root = createRoot(container)
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
  await act(async () => root.unmount())
  container.remove()
  await window.happyDOM.close()
  globalThis.fetch = originalFetch
  for (const [name, descriptor] of originalGlobals) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor)
    else Reflect.deleteProperty(globalThis, name)
  }
  originalGlobals.clear()
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
  assert.equal(container.querySelector('[aria-label="连接详情"]'), null)
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
