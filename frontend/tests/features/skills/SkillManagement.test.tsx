import { createDom } from '../../support/dom'
import { skill } from '../../fixtures/skills'
import { response } from '../../fixtures/http'
import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { act } from 'react'
import type { Root } from 'react-dom/client'
import { SkillManagement } from '@/features/skills/SkillManagement'

let dispose: () => Promise<void>
let container: HTMLDivElement
let root: Root
const calls: string[] = []

const changed = () => {}
beforeEach(async () => {
  const dom = await createDom()
  container = dom.container
  root = dom.root
  dispose = dom.dispose
  calls.length = 0
  globalThis.fetch = async (url) => {
    calls.push(String(url))
    if (String(url).includes('/versions?'))
      return response({ records: [], total: 0, page: 1, size: 20 })
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
  await dispose()
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

test('卡片入口进入只读详情，返回后仍显示列表', async () => {
  await render()
  const entry = container.querySelector<HTMLButtonElement>(
    'button[aria-label="查看 文档审阅 详情"]',
  )
  assert.ok(entry)
  await act(async () => entry.click())
  assert.equal(container.querySelector('h1')?.textContent, skill.name)
  assert.equal(container.querySelectorAll('input, textarea, [role="combobox"]').length, 0)
  assert.equal(container.querySelector('dl')?.textContent?.includes(skill.description), true)
  await act(async () => find('button', 'Skill 列表').click())
  assert.equal(container.querySelector('h1')?.textContent?.includes('Skill 管理'), true)
})
