import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { Window } from 'happy-dom'
import { act } from 'react'
import type { Root } from 'react-dom/client'
import { SkillManagement } from './SkillManagement'

const originalFetch = globalThis.fetch
const descriptors = new Map<string, PropertyDescriptor | undefined>()
let window: Window, container: HTMLDivElement, root: Root
const calls: string[] = []
const skill = {
  id: 1,
  slug: 'review',
  name: '文档审阅',
  description: '审阅文档',
  tags: ['文档'],
  origin: 'CUSTOM',
  enabled: true,
  latestVersion: 2,
  revision: 0,
  updatedAt: '2026-10-23T12:00:00Z',
  fileCount: 3,
  contentUpdatedAt: '2026-10-22T12:00:00Z',
}
const changed = () => {}
beforeEach(async () => {
  window = new Window()
  for (const [name, value] of Object.entries({
    window,
    document: window.document,
    HTMLElement: window.HTMLElement,
    HTMLButtonElement: window.HTMLButtonElement,
    HTMLInputElement: window.HTMLInputElement,
    HTMLTextAreaElement: window.HTMLTextAreaElement,
    Element: window.Element,
    Node: window.Node,
    Event: window.Event,
    KeyboardEvent: window.KeyboardEvent,
    getComputedStyle: window.getComputedStyle.bind(window),
    requestAnimationFrame: window.requestAnimationFrame.bind(window),
    cancelAnimationFrame: window.cancelAnimationFrame.bind(window),
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    descriptors.set(name, Object.getOwnPropertyDescriptor(globalThis, name))
    Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
  }
  container = document.createElement('div')
  document.body.append(container)
  const { createRoot } = await import('react-dom/client')
  root = createRoot(container)
  calls.length = 0
  globalThis.fetch = async (url) => {
    calls.push(String(url))
    const search = new URL(String(url), 'http://localhost').searchParams
    const source = search.get('origin')
    return Response.json({
      code: 0,
      message: 'success',
      data: {
        records: source === 'SYSTEM' ? [] : [skill],
        total: source === 'SYSTEM' ? 0 : 25,
        page: Number(search.get('page')),
        size: 12,
        counts: { custom: 25, system: 0 },
      },
    })
  }
})
afterEach(async () => {
  await act(async () => root.unmount())
  container.remove()
  await window.happyDOM.close()
  globalThis.fetch = originalFetch
  for (const [name, value] of descriptors) {
    if (value) Object.defineProperty(globalThis, name, value)
    else Reflect.deleteProperty(globalThis, name)
  }
  descriptors.clear()
})
async function render() {
  await act(async () =>
    root.render(<SkillManagement tenantId={7} canManageBuiltIn={false} onDirtyChange={changed} />),
  )
}
function find(selector: string, text: string) {
  const item = Array.from(container.querySelectorAll<HTMLButtonElement>(selector)).find((el) =>
    el.textContent?.includes(text),
  )
  assert.ok(item, `缺少控件：${text}`)
  return item
}
test('来源筛选回到第一页并使用服务端总量，市场保持禁用', async () => {
  await render()
  assert.equal(find('[role="tab"]', '市场').disabled, true)
  assert.equal(container.textContent?.includes('v2 · 3 个文件'), true)
  await act(async () => find('button', '下一页').click())
  assert.equal(calls.at(-1)?.includes('page=2'), true)
  await act(async () =>
    find('[role="tab"]', '内置').dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }),
    ),
  )
  assert.equal(calls.at(-1)?.includes('page=1'), true)
  assert.equal(calls.at(-1)?.includes('origin=SYSTEM'), true)
  assert.equal(container.textContent?.includes('没有找到匹配的 Skill'), true)
  assert.equal(find('[role="tab"]', '自建').textContent?.includes('25'), true)
  await act(async () => find('button', '清除筛选').click())
  assert.equal(calls.at(-1)?.includes('origin='), false)
  assert.equal(container.textContent?.includes('文档审阅'), true)
})
test('加载失败提供重试，后续成功恢复列表', async () => {
  const success = globalThis.fetch
  globalThis.fetch = async () =>
    Response.json({ code: 503, message: '暂时不可用', data: null }, { status: 503 })
  await render()
  assert.equal(container.querySelector('[role="alert"]')?.textContent?.includes('暂时不可用'), true)
  globalThis.fetch = success
  await act(async () => find('button', '重新加载').click())
  assert.equal(container.querySelector('[role="alert"]') === null, true)
  assert.equal(container.textContent?.includes('文档审阅'), true)
})
