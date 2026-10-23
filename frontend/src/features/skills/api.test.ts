import { afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { clearCsrf } from '@/lib/api'
import { isContent, isSkillPage, skillApi } from './api'
const originalFetch = globalThis.fetch
afterEach(() => {
  globalThis.fetch = originalFetch
  clearCsrf()
})
function response(data: unknown) {
  return Response.json({ code: 0, message: 'success', data })
}
test('整包保存使用 multipart 和 CSRF，包内名称描述原样保留', async () => {
  globalThis.fetch = async (url, options) => {
    if (url === '/api/auth/csrf')
      return response({ headerName: 'X-CSRF-TOKEN', token: 'fake-token' })
    assert.equal(String(url), '/api/tenants/7/skills/9/versions')
    const headers = new Headers(options?.headers)
    assert.equal(headers.get('Content-Type'), null)
    assert.equal(headers.get('X-CSRF-TOKEN'), 'fake-token')
    assert.ok(options?.body instanceof FormData)
    assert.equal(
      options.body.get('input'),
      JSON.stringify({
        baseVersion: 2,
        requestId: 'request-id',
        paths: ['SKILL.md', 'assets/a.bin'],
      }),
    )
    const files = options.body.getAll('files')
    assert.ok(files[0] instanceof Blob)
    assert.ok(files[1] instanceof Blob)
    assert.equal(await files[0].text(), '---\nname: package-name\ndescription: 包内描述\n---')
    assert.deepEqual(new Uint8Array(await files[1].arrayBuffer()), new Uint8Array([0, 1, 255]))
    return response({
      number: 3,
      fileCount: 2,
      totalBytes: 60,
      createdBy: 1,
      createdAt: '2026-10-22T12:00:00Z',
    })
  }
  assert.equal(
    (
      await skillApi.save(7, 9, 2, 'request-id', [
        {
          path: 'SKILL.md',
          text: '---\nname: package-name\ndescription: 包内描述\n---',
          base64: null,
          size: 0,
        },
        { path: 'assets/a.bin', text: null, base64: 'AAH/', size: 3 },
      ])
    ).number,
    3,
  )
})
test('内容响应拒绝缺失数据和同时出现文本与二进制的响应', () => {
  assert.equal(
    isContent({ number: 1, files: [{ path: 'SKILL.md', size: 1, text: 'a', base64: null }] }),
    true,
  )
  for (const file of [
    { path: 'x', size: 1, text: null, base64: null },
    { path: 'x', size: 1, text: 'a', base64: 'YQ==' },
    { path: 'x', size: -1, text: 'a', base64: null },
  ])
    assert.equal(isContent({ number: 1, files: [file] }), false)
})

test('来源筛选发送到服务端，工作区数量独立于当前页', async () => {
  globalThis.fetch = async (url) => {
    assert.equal(
      String(url),
      '/api/tenants/7/skills?page=2&size=12&q=%E6%A0%87%E7%AD%BE&origin=CUSTOM',
    )
    return response({
      records: [],
      total: 13,
      page: 2,
      size: 12,
      counts: { custom: 13, system: 4 },
    })
  }
  const page = await skillApi.list(7, 2, '标签', 'CUSTOM')
  assert.equal(page.total, 13)
  assert.deepEqual(page.counts, { custom: 13, system: 4 })
  for (const counts of [null, {}, { custom: -1, system: 0 }, { custom: 1, system: '4' }])
    assert.equal(isSkillPage({ records: [], total: 0, page: 1, size: 12, counts }), false)
})
