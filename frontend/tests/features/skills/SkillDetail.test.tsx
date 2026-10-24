import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { act, useState } from 'react'
import type { Root } from 'react-dom/client'
import { toast } from 'sonner'
import { SkillDetail } from '@/features/skills/SkillDetail'
import { createDom } from '../../support/dom'
import { response } from '../../fixtures/http'
import { skill } from '../../fixtures/skills'

let dispose: () => Promise<void>
let container: HTMLDivElement
let root: Root
let dirty = false
let backCount = 0
const writes: unknown[] = []
const changed = (value: boolean) => {
  dirty = value
}
const back = () => {
  backCount += 1
}

beforeEach(async () => {
  const dom = await createDom()
  container = dom.container
  root = dom.root
  dispose = dom.dispose
  dirty = false
  backCount = 0
  writes.length = 0
  globalThis.fetch = async (url, options) => {
    if (String(url) === '/api/auth/csrf')
      return response({ headerName: 'X-CSRF-TOKEN', token: 'fake' })
    if (options?.method === 'PATCH') {
      const payload: unknown = JSON.parse(String(options.body))
      writes.push(payload)
      assert.ok(typeof payload === 'object' && payload !== null)
      return response({ ...skill, ...payload, revision: skill.revision + 1 })
    }
    return response({ records: [], total: 0, page: 1, size: 20 })
  }
})
afterEach(async () => {
  await dispose()
})

function Detail({ canManage }: { canManage: boolean }) {
  const [current, setCurrent] = useState(skill)
  return (
    <SkillDetail
      tenantId={7}
      skill={current}
      canManage={canManage}
      onUpdated={setCurrent}
      onDirtyChange={changed}
      onBack={back}
    />
  )
}
async function render(canManage = true) {
  await act(async () => root.render(<Detail canManage={canManage} />))
}
function button(text: string, scope: ParentNode = container) {
  const found = Array.from(scope.querySelectorAll('button')).find(
    (el) => el.textContent?.trim() === text,
  )
  assert.ok(found, `缺少按钮：${text}`)
  return found
}
async function rename(value: string) {
  const input = container.querySelector('input')
  assert.ok(input)
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(input, value)
    input.dispatchEvent(new Event('input', { bubbles: true }))
  })
}
function assertReading(name = skill.name) {
  assert.equal(container.querySelectorAll('input, textarea, [role="combobox"]').length, 0)
  assert.equal(container.querySelector('form') === null, true)
  assert.equal(container.querySelector('dl')?.textContent?.includes(name), true)
  assert.equal(container.querySelector('dl')?.textContent?.includes(skill.description), true)
}

test('默认详情展示已保存的信息，只有进入编辑后显示表单；取消恢复原值', async () => {
  await render()
  assertReading()
  assert.equal(dirty, false)
  await act(async () => button('编辑').click())
  assert.equal(container.querySelector('input')?.value, skill.name)
  assert.equal(document.activeElement?.tagName, 'INPUT')
  assert.equal(container.querySelector('textarea')?.value, skill.description)
  assert.equal(button('保存基本信息').disabled, true)
  await rename('未保存的名称')
  assert.equal(dirty, true)
  await act(async () => button('取消').click())
  assertReading()
  assert.equal(document.activeElement?.textContent?.trim(), '编辑')
  assert.equal(dirty, false)
  assert.equal(writes.length, 0)
  await act(async () => button('编辑').click())
  assert.equal(container.querySelector('input')?.value, skill.name)
})

test('保存后回到只读详情并展示服务端返回的新信息', async () => {
  await render()
  await act(async () => button('编辑').click())
  await rename('审阅助手')
  await act(async () => button('保存基本信息').click())
  assertReading('审阅助手')
  assert.equal(container.querySelector('h1')?.textContent, '审阅助手')
  assert.equal(dirty, false)
  assert.deepEqual(writes, [
    {
      name: '审阅助手',
      description: skill.description,
      tags: skill.tags,
      enabled: true,
      revision: 0,
    },
  ])
  await act(async () => button('编辑').click())
  assert.equal(container.querySelector('input')?.value, '审阅助手')
})

test('保存期间阻止重复提交与离开；失败保留草稿并可重试', async () => {
  const fallback = globalThis.fetch
  const pending = Promise.withResolvers<Response>()
  let attempts = 0
  globalThis.fetch = async (url, options) => {
    if (options?.method === 'PATCH' && ++attempts === 1) return pending.promise
    return fallback(url, options)
  }
  await render()
  await act(async () => button('编辑').click())
  await rename('保留草稿')
  await act(async () => button('保存基本信息').click())
  assert.equal(button('正在保存…').disabled, true)
  assert.equal(button('取消').disabled, true)
  await act(async () => {
    button('取消').click()
    button('Skill 列表').click()
  })
  assert.equal(backCount, 0)
  assert.equal(attempts, 1)
  const history = toast.getHistory().length
  await act(async () =>
    pending.resolve(
      Response.json({ code: 503, message: '模拟保存失败', data: null }, { status: 503 }),
    ),
  )
  assert.equal(container.querySelector('input')?.value, '保留草稿')
  assert.equal(dirty, true)
  assert.equal(button('保存基本信息').disabled, false)
  assert.equal(
    toast
      .getHistory()
      .slice(history)
      .some((item) => 'title' in item && item.title === '模拟保存失败'),
    true,
  )
  await act(async () => button('保存基本信息').click())
  assertReading('保留草稿')
  assert.equal(attempts, 2)
})

test('未修改的编辑页面可以直接返回列表并退出编辑态', async () => {
  await render()
  await act(async () => button('编辑').click())
  assert.equal(container.querySelector('form') !== null, true)
  await act(async () => button('Skill 列表').click())
  assertReading()
  assert.equal(backCount, 1)
  assert.equal(dirty, false)
})

test('没有管理权限时保持只读且不显示编辑、删除入口', async () => {
  await render(false)
  assertReading()
  assert.equal(
    Array.from(container.querySelectorAll('button')).some((el) =>
      ['编辑', '删除'].includes(el.textContent?.trim() ?? ''),
    ),
    false,
  )
})
