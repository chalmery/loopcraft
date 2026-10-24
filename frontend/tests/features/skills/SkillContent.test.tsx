import { createDom } from '../../support/dom'
import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { act } from 'react'
import { toast } from 'sonner'
import type { Root } from 'react-dom/client'
import { SkillContent } from '@/features/skills/SkillContent'
let dispose: () => Promise<void>
let container: HTMLDivElement
let root: Root
let saved = false
let dirty = false
beforeEach(async () => {
  const dom = await createDom()
  container = dom.container
  root = dom.root
  dispose = dom.dispose
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
  await dispose()
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
  const before = toast.getHistory().length
  await act(async () => button('保存为 v2').click())
  assert.equal(
    toast
      .getHistory()
      .slice(before)
      .some((item) => 'title' in item && item.title === '模拟存储失败'),
    true,
  )
  assert.equal(container.querySelector('textarea')?.value, '原始内容')
  assert.equal(dirty, true)
  assert.equal(saved, false)
  assert.equal(button('保存为 v2').disabled, false)
})

test('版本切换后旧内容的迟到响应不能覆盖当前文件', async () => {
  const previous = Promise.withResolvers<Response>()
  const latest = Promise.withResolvers<Response>()
  globalThis.fetch = async (url) =>
    String(url).endsWith('/versions/1') ? previous.promise : latest.promise
  await render(1, 2)
  await render(2, 2)
  assert.equal(container.querySelector('textarea') === null, true)
  await act(async () =>
    latest.resolve(
      Response.json({
        code: 0,
        message: 'success',
        data: {
          number: 2,
          files: [{ path: 'SKILL.md', text: '第二版内容', base64: null, size: 15 }],
        },
      }),
    ),
  )
  await act(async () =>
    previous.resolve(
      Response.json({
        code: 0,
        message: 'success',
        data: { number: 1, files: [{ path: 'SKILL.md', text: '旧内容', base64: null, size: 9 }] },
      }),
    ),
  )
  assert.equal(container.querySelector('textarea')?.value, '第二版内容')
})

test('切换资源后旧保存结果不能触发当前资源的成功回调', async () => {
  const save = Promise.withResolvers<Response>()
  const fallback = globalThis.fetch
  globalThis.fetch = async (url, options) =>
    options?.method === 'POST' ? save.promise : fallback(url, options)
  await render(1, 1)
  await act(async () => button('编辑最新内容').click())
  await act(async () => button('保存为 v2').click())
  assert.equal(button('正在保存…').disabled, true)
  await render(2, 2)
  const before = toast.getHistory().length
  await act(async () =>
    save.resolve(
      Response.json({
        code: 0,
        message: 'success',
        data: { number: 2, fileCount: 1, totalBytes: 12, createdBy: 1, createdAt: '' },
      }),
    ),
  )
  assert.equal(saved, false)
  assert.equal(dirty, false)
  assert.equal(toast.getHistory().length, before)
})

test('保存失败重试复用请求标识，文件修改后使用新标识', async () => {
  const fallback = globalThis.fetch
  const ids: string[] = []
  globalThis.fetch = async (url, options) => {
    if (options?.method !== 'POST') return fallback(url, options)
    assert.ok(options.body instanceof FormData)
    const input: unknown = JSON.parse(String(options.body.get('input')))
    assert.ok(
      typeof input === 'object' &&
        input !== null &&
        'requestId' in input &&
        typeof input.requestId === 'string',
    )
    ids.push(input.requestId)
    return Response.json({ code: 503, message: '模拟重试失败', data: null }, { status: 503 })
  }
  await render(1, 1)
  await act(async () => button('编辑最新内容').click())
  await act(async () => button('保存为 v2').click())
  await act(async () => button('保存为 v2').click())
  assert.equal(ids[0], ids[1])
  const textarea = container.querySelector('textarea')
  assert.ok(textarea)
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')?.set?.call(
      textarea,
      '修改后的草稿',
    )
    textarea.dispatchEvent(new Event('input', { bubbles: true }))
  })
  await act(async () => button('保存为 v2').click())
  assert.equal(ids.length, 3)
  assert.notEqual(ids[1], ids[2])
  assert.equal(textarea.value, '修改后的草稿')
})
