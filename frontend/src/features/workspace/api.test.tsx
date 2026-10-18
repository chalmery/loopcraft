import { afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { renderToStaticMarkup } from 'react-dom/server'
import { workspaceApi, type Member } from './api'
import { WorkspaceSection } from './WorkspaceSection'

const originalFetch = globalThis.fetch

afterEach(() => {
  globalThis.fetch = originalFetch
})

function members(count: number): Member[] {
  return Array.from({ length: count }, (_, index) => ({
    userId: index + 1,
    username: `user${index + 1}`,
    displayName: `成员 ${index + 1}`,
    role: index === count - 1 ? 'OWNER' : 'MEMBER',
    enabled: true,
  }))
}

for (const count of [101, 200]) {
  test(`${count} 位成员读取全部分页，包含末页负责人`, async () => {
    const all = members(count)
    const pages: number[] = []
    globalThis.fetch = async (input) => {
      const url = new URL(String(input), 'http://test')
      const page = Number(url.searchParams.get('page'))
      pages.push(page)
      return Response.json({
        code: 0,
        message: 'success',
        data: all.slice((page - 1) * 100, page * 100),
      })
    }
    assert.deepEqual(await workspaceApi.members(1), all)
    assert.deepEqual(pages, count === 101 ? [1, 2] : [1, 2, 3])
  })
}

test('后续成员页失败时报告失败，不把部分列表当作完整结果', async () => {
  globalThis.fetch = async (input) => {
    if (String(input).includes('page=2')) throw new Error('第二页加载失败')
    return Response.json({ code: 0, message: 'success', data: members(100) })
  }
  await assert.rejects(workspaceApi.members(1), /第二页加载失败/)
})

test('负责人资料权限不依赖成员列表是否已加载', () => {
  const html = renderToStaticMarkup(
    <WorkspaceSection
      tenant={{ id: 1, name: '测试租户', updatedAt: '', myRole: 'OWNER' }}
      tenants={[]}
      members={[]}
      user={{
        id: 101,
        username: 'owner',
        displayName: '负责人',
        enabled: true,
        platformAdmin: false,
      }}
      canManageMembers
      busy={false}
      onRename={async () => true}
      onAddMember={async () => {}}
      onRemoveMember={async () => {}}
    />,
  )
  assert.match(html, /修改名称/)
  assert.doesNotMatch(html, /仅负责人可修改/)
})
