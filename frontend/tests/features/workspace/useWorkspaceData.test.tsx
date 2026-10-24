import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { act } from 'react'
import type { Root } from 'react-dom/client'
import { useWorkspaceData } from '@/features/workspace/useWorkspaceData'
import { createDom } from '../../support/dom'
import { response } from '../../fixtures/http'

let root: Root
let container: HTMLDivElement
let dispose: () => Promise<void>
beforeEach(async () => {
  const dom = await createDom()
  root = dom.root
  container = dom.container
  dispose = dom.dispose
})
afterEach(async () => {
  await dispose()
})
function Probe() {
  const data = useWorkspaceData(1)
  return (
    <>
      <button onClick={() => void data.reload()}>刷新</button>
      <button onClick={() => data.setTenantId(1)}>租户一</button>
      <button onClick={() => data.setTenantId(2)}>租户二</button>
      <output>{data.tenants.map((tenant) => tenant.name).join(',')}</output>
      <ul>
        {data.currentMembers.map((member) => (
          <li key={member.userId}>{member.displayName}</li>
        ))}
      </ul>
    </>
  )
}
function button(name: string) {
  const button = Array.from(container.querySelectorAll('button')).find(
    (item) => item.textContent === name,
  )
  assert.ok(button)
  return button
}
const tenants = [
  { id: 1, name: '一', updatedAt: '', myRole: 'OWNER' },
  { id: 2, name: '二', updatedAt: '', myRole: 'OWNER' },
]
const member = (displayName: string) => ({
  userId: 1,
  username: 'test',
  displayName,
  role: 'OWNER',
  enabled: true,
})

test('手动刷新返回后，初始化的旧响应不能覆盖新列表', async () => {
  const old = Promise.withResolvers<Response>()
  let calls = 0
  globalThis.fetch = async () => (++calls === 1 ? old.promise : response(tenants))
  await act(async () => root.render(<Probe />))
  await act(async () => button('刷新').click())
  await act(async () => old.resolve(response([{ id: 3, name: '过期列表', updatedAt: '' }])))
  assert.equal(container.querySelector('output')?.textContent, '一,二')
})

test('旧租户的手动刷新不能覆盖新租户已返回的成员', async () => {
  const old = Promise.withResolvers<Response>()
  let firstTenantReads = 0
  globalThis.fetch = async (url) => {
    const path = String(url)
    if (path.includes('/tenants/1/members'))
      return ++firstTenantReads === 1 ? response([member('原成员')]) : old.promise
    if (path.includes('/tenants/2/members')) return response([member('新租户成员')])
    return response(tenants)
  }
  await act(async () => root.render(<Probe />))
  await act(async () => button('租户一').click())
  await act(async () => button('刷新').click())
  await act(async () => button('租户二').click())
  await act(async () => old.resolve(response([member('迟到的旧成员')])))
  assert.equal(container.querySelector('ul')?.textContent, '新租户成员')
})
