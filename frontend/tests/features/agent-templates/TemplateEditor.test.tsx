import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { act } from 'react'
import type { Root } from 'react-dom/client'
import { TemplateEditor } from '@/features/agent-templates/TemplateEditor'
import { isRecord } from '@/lib/contracts'
import { createDom } from '../../support/dom'
import { response } from '../../fixtures/http'
import { template, templateModel } from '../../fixtures/templates'

let root: Root
let container: HTMLDivElement
let dispose: () => Promise<void>
let dirty = false
let failed = false
let conflict = false
let saved = 0
let backed = 0
const writes: unknown[] = []
const onDirty = (value: boolean) => {
  dirty = value
}
const onPending = () => {}
beforeEach(async () => {
  const dom = await createDom()
  root = dom.root
  container = dom.container
  dispose = dom.dispose
  dirty = false
  failed = false
  conflict = false
  saved = 0
  backed = 0
  writes.length = 0
  globalThis.fetch = async (url, options) => {
    const path = String(url)
    if (path === '/api/auth/csrf')
      return response({ headerName: 'X-CSRF-TOKEN', token: 'test-only' })
    if (options?.method === 'PUT' || options?.method === 'POST') {
      const payload: unknown = JSON.parse(String(options.body))
      writes.push(payload)
      if (conflict)
        return Response.json({ code: 409, message: '其他成员已更新', data: null }, { status: 409 })
      if (failed)
        return Response.json({ code: 500, message: '临时失败', data: null }, { status: 500 })
      assert.ok(isRecord(payload))
      return response({
        ...template,
        ...payload,
        skillBindings: template.skillBindings,
        revision: 3,
      })
    }
    if (path.includes('/agent-templates/models'))
      return response({ records: [templateModel], page: 1, size: 20, total: 1 })
    if (path.includes('/skills/1/versions'))
      return response({
        records: [
          { number: 2, fileCount: 1, totalBytes: 1, createdBy: 1, createdAt: template.updatedAt },
          { number: 1, fileCount: 1, totalBytes: 1, createdBy: 1, createdAt: template.updatedAt },
        ],
        total: 2,
        page: 1,
        size: 20,
      })
    if (path.includes('/skills?'))
      return response({
        records: [],
        page: 1,
        size: 12,
        total: 0,
        counts: { custom: 0, system: 0 },
      })
    return response(template)
  }
})
afterEach(async () => dispose())
async function render(id: number | null = 9) {
  await act(async () =>
    root.render(
      <TemplateEditor
        tenantId={7}
        id={id}
        copy={false}
        onBack={() => {
          backed++
        }}
        onSaved={() => {
          saved++
        }}
        onCopy={() => {}}
        onDirtyChange={onDirty}
        onPendingChange={onPending}
      />,
    ),
  )
}
function button(name: string) {
  const result = Array.from(container.querySelectorAll('button')).find(
    (el) => el.textContent?.trim() === name,
  )
  assert.ok(result, `缺少按钮 ${name}`)
  return result
}
async function tab(name: string) {
  const result = Array.from(container.querySelectorAll<HTMLButtonElement>('[role="tab"]')).find(
    (el) => el.textContent?.trim().startsWith(name),
  )
  assert.ok(result)
  await act(async () => {
    result.focus()
    result.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }))
    result.click()
  })
}
async function input(id: string, value: string) {
  const element = container.querySelector(`[id="${id}"]`)
  assert.ok(element instanceof HTMLInputElement)
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(element, value)
    element.dispatchEvent(new Event('input', { bubbles: true }))
  })
}
async function save() {
  const form = container.querySelector('form')
  assert.ok(form)
  await act(async () => {
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
  })
}

test('跨页签修改统一保存，保留零与小数分钟，保存后留在当前页签', async () => {
  await render()
  assert.equal(button('保存模板').disabled, true)
  await input('name', '协作助手')
  await tab('运行设置')
  await input('timeoutMinutes', '0.5')
  await save()
  assert.equal(saved, 1)
  assert.equal(dirty, false)
  assert.equal(container.querySelector<HTMLInputElement>('#timeoutMinutes')?.value, '0.5')
  assert.equal(
    container.querySelector('[role="tab"][aria-selected="true"]')?.textContent,
    '运行设置',
  )
  assert.ok(isRecord(writes[0]))
  assert.equal(writes[0].contextBudgetTokens, null)
  assert.equal(writes[0].maxIterations, 0)
  assert.equal(writes[0].timeoutMinutes, 0.5)
})
test('多个栏目错误同时标记，切回基本信息且不提交非法数据', async () => {
  await render()
  await input('name', '')
  await tab('运行设置')
  await input('maxIterations', '-1')
  await save()
  assert.equal(writes.length, 0)
  assert.match(
    container.querySelector('[role="tab"][aria-selected="true"]')?.textContent ?? '',
    /基本信息/,
  )
  assert.equal(container.querySelector('#name')?.getAttribute('aria-invalid'), 'true')
  assert.equal(container.querySelector('#maxIterations')?.getAttribute('aria-invalid'), 'true')
})
test('保存失败保留草稿，重试复用请求标识，内容改变后换标识', async () => {
  await render()
  await input('name', '失败后保留')
  failed = true
  await save()
  await save()
  assert.equal(saved, 0)
  assert.equal(dirty, true)
  assert.equal(container.querySelector<HTMLInputElement>('#name')?.value, '失败后保留')
  assert.ok(isRecord(writes[0]) && isRecord(writes[1]))
  assert.equal(writes[0].requestId, writes[1].requestId)
  await input('name', '继续修改')
  failed = false
  await save()
  assert.ok(isRecord(writes[2]))
  assert.notEqual(writes[0].requestId, writes[2].requestId)
  assert.equal(saved, 1)
})
test('修订冲突保留草稿，不自动覆盖或重新加载', async () => {
  await render()
  await input('name', '当前草稿')
  conflict = true
  await save()
  assert.match(container.textContent ?? '', /其他成员已更新模板/)
  assert.equal(container.querySelector<HTMLInputElement>('#name')?.value, '当前草稿')
  assert.equal(saved, 0)
  assert.equal(dirty, true)
})
test('未保存离开不会直接返回列表，当前输入保持不变', async () => {
  await render()
  await input('name', '尚未保存')
  await act(async () => button('取消').click())
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 20))
  })
  assert.equal(backed, 0)
  assert.equal(dirty, true)
  assert.equal(container.querySelector<HTMLInputElement>('#name')?.value, '尚未保存')
})
