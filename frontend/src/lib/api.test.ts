import { afterEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { ApiError, clearCsrf, isNull, onSessionExpired, refreshCsrf, request } from './api'
import { authApi } from '@/features/auth/api'

const originalFetch = globalThis.fetch
const subscriptions: (() => void)[] = []

afterEach(() => {
  globalThis.fetch = originalFetch
  clearCsrf()
  for (const unsubscribe of subscriptions.splice(0)) unsubscribe()
})

function response(data: unknown, status = 200, code = status === 200 ? 0 : status) {
  return Response.json({ code, message: status === 200 ? 'success' : '请求失败', data }, { status })
}

function watchExpiry() {
  const events: string[] = []
  subscriptions.push(onSessionExpired(() => events.push('expired')))
  return events
}

test('失效 CSRF 刷新后重试一次，并使用新令牌', async () => {
  let reads = 0
  const tokens: (string | null)[] = []
  globalThis.fetch = async (url, options) => {
    if (url === '/api/auth/csrf') {
      return response({ headerName: 'X-CSRF-TOKEN', token: `token-${++reads}` })
    }
    tokens.push(new Headers(options?.headers).get('X-CSRF-TOKEN'))
    return tokens.length === 1 ? response(null, 403, 40301) : response(null)
  }
  await request('/tenants/1/members', isNull, { method: 'POST', body: '{"userId":2}' })
  assert.deepEqual(tokens, ['token-1', 'token-2'])
})

test('重复 CSRF 失败不无限重试', async () => {
  let writes = 0
  globalThis.fetch = async (url) => {
    if (url === '/api/auth/csrf') return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
    writes += 1
    return response(null, 403, 40301)
  }
  await assert.rejects(request('/users/me', isNull, { method: 'PATCH' }), ApiError)
  assert.equal(writes, 2)
})

test('业务权限不足不会重放写操作或清理会话', async () => {
  let writes = 0
  let reads = 0
  const events = watchExpiry()
  globalThis.fetch = async (url) => {
    if (url === '/api/auth/csrf') {
      reads += 1
      return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
    }
    writes += 1
    return response(null, 403)
  }
  await assert.rejects(request('/admin/users', isNull, { method: 'POST' }), {
    status: 403,
    code: 403,
  })
  assert.equal(writes, 1)
  assert.equal(reads, 1)
  assert.deepEqual(events, [])
})

test('会话过期后 CSRF 恢复得到 401，通知界面返回登录', async () => {
  const events = watchExpiry()
  let writes = 0
  globalThis.fetch = async (url) => {
    if (url === '/api/auth/csrf') return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
    return ++writes === 1 ? response(null, 403, 40301) : response(null, 401)
  }
  await assert.rejects(request('/users/me', isNull, { method: 'PATCH' }), { status: 401 })
  assert.equal(writes, 2)
  assert.deepEqual(events, ['expired'])
})

test('普通请求 401 即使响应不是 JSON 也清理会话', async () => {
  const events = watchExpiry()
  globalThis.fetch = async () => new Response('Unauthorized', { status: 401 })
  await assert.rejects(request('/users/me', isNull), { status: 401 })
  assert.deepEqual(events, ['expired'])
})

test('错误登录凭证不会触发全局会话失效', async () => {
  const events = watchExpiry()
  globalThis.fetch = async (url) =>
    url === '/api/auth/csrf'
      ? response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
      : response(null, 401)
  await assert.rejects(authApi.login('tester', 'wrong-password'), { status: 401 })
  assert.deepEqual(events, [])
})

test('会话已过期时退出登录也能恢复 CSRF 并成功退出', async () => {
  let writes = 0
  let reads = 0
  globalThis.fetch = async (url) => {
    if (url === '/api/auth/csrf') {
      reads += 1
      return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
    }
    return ++writes === 1 ? response(null, 403, 40301) : response(null)
  }
  await authApi.logout()
  assert.equal(writes, 2)
  assert.equal(reads, 2)
})

test('并发写请求复用同一次 CSRF 读取', async () => {
  let reads = 0
  globalThis.fetch = async (url) => {
    if (url === '/api/auth/csrf') {
      reads += 1
      return response({ headerName: 'X-CSRF-TOKEN', token: 'token' })
    }
    return response(null)
  }
  await Promise.all([
    request('/tenants/1/members', isNull, { method: 'POST' }),
    request('/tenants/2/members', isNull, { method: 'POST' }),
  ])
  assert.equal(reads, 1)
})

test('旧会话的迟到 401 不清理新会话', async () => {
  const events = watchExpiry()
  const pending = Promise.withResolvers<Response>()
  globalThis.fetch = () => pending.promise
  const oldRequest = request('/users/me', isNull)
  clearCsrf()
  pending.resolve(response(null, 401))
  await assert.rejects(oldRequest, { status: 401 })
  assert.deepEqual(events, [])
})

test('旧会话的 CSRF 响应不能覆盖新令牌', async () => {
  const pending = Promise.withResolvers<Response>()
  let reads = 0
  let sentToken: string | null = null
  globalThis.fetch = async (url, options) => {
    if (url === '/api/auth/csrf') {
      if (++reads === 1) return pending.promise
      return response({ headerName: 'X-CSRF-TOKEN', token: 'new-token' })
    }
    sentToken = new Headers(options?.headers).get('X-CSRF-TOKEN')
    return response(null)
  }
  const previous = refreshCsrf()
  clearCsrf()
  await refreshCsrf()
  pending.resolve(response({ headerName: 'X-CSRF-TOKEN', token: 'old-token' }))
  await previous
  await request('/users/me', isNull, { method: 'PATCH' })
  assert.equal(sentToken, 'new-token')
})
