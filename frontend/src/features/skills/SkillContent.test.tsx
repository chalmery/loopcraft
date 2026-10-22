import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { Window } from 'happy-dom'
import { act } from 'react'
import type { Root } from 'react-dom/client'
import { clearCsrf } from '@/lib/api'
import { SkillContent } from './SkillContent'
const fetchBefore = globalThis.fetch
const descriptors = new Map<string, PropertyDescriptor | undefined>()
let window: Window, container: HTMLDivElement, root: Root
let saved = false,
  dirty = false
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
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    descriptors.set(name, Object.getOwnPropertyDescriptor(globalThis, name))
    Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
  }
  container = document.createElement('div')
  document.body.append(container)
  const { createRoot } = await import('react-dom/client')
  root = createRoot(container)
  saved = false
  dirty = false
  globalThis.fetch = async (url, options) => {
    if (url === '/api/auth/csrf')
      return Response.json({
        code: 0,
        message: 'success',
        data: { headerName: 'X-CSRF-TOKEN', token: 'fake' },
      })
    if (options?.method === 'POST')
      return Response.json({ code: 503, message: '模拟存储失败', data: null }, { status: 503 })
    return Response.json({
      code: 0,
      message: 'success',
      data: { number: 1, files: [{ path: 'SKILL.md', text: '原始内容', base64: null, size: 12 }] },
    })
  }
})
afterEach(async () => {
  await act(async () => root.unmount())
  container.remove()
  await window.happyDOM.close()
  globalThis.fetch = fetchBefore
  clearCsrf()
  for (const [name, value] of descriptors) {
    if (value) Object.defineProperty(globalThis, name, value)
    else Reflect.deleteProperty(globalThis, name)
  }
  descriptors.clear()
})
const changed = (value: boolean) => {
  dirty = value
}
async function render(number: number, latest: number) {
  await act(async () =>
    root.render(
      <SkillContent
        tenantId={1}
        skillId={1}
        number={number}
        latest={latest}
        canManage
        onSaved={() => {
          saved = true
        }}
        onDirtyChange={changed}
      />,
    ),
  )
}
function button(text: string) {
  const found = Array.from(container.querySelectorAll('button')).find(
    (el) => el.textContent === text,
  )
  assert.ok(found, `missing button ${text}`)
  return found
}
test('历史版本不能以旧内容生成新版本', async () => {
  await render(1, 2)
  assert.equal(
    Array.from(container.querySelectorAll('button')).some(
      (el) => el.textContent === '编辑最新内容',
    ),
    false,
  )
  assert.equal(container.querySelector('textarea')?.readOnly, true)
})
test('保存失败保留草稿并显示错误，不触发成功回调', async () => {
  await render(1, 1)
  await act(async () => button('编辑最新内容').click())
  assert.equal(dirty, true)
  assert.equal(container.querySelector('textarea')?.readOnly, false)
  await act(async () => button('保存为 v2').click())
  assert.equal(
    container.querySelector('[role="alert"]')?.textContent?.includes('模拟存储失败'),
    true,
  )
  assert.equal(container.querySelector('textarea')?.value, '原始内容')
  assert.equal(dirty, true)
  assert.equal(saved, false)
  assert.equal(button('保存为 v2').disabled, false)
})
