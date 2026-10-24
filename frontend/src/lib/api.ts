type Envelope<T> = { code: number; message: string; data: T }
export type Validator<T> = (value: unknown) => value is T

type Csrf = { headerName: string; token: string }

let csrf: Csrf | null = null
let csrfRequest: Promise<void> | null = null
let sessionVersion = 0
const sessionExpiredListeners = new Set<() => void>()

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code: number,
    readonly field?: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function clearCsrf(): void {
  csrf = null
  csrfRequest = null
  sessionVersion += 1
}

export function onSessionExpired(listener: () => void): () => void {
  sessionExpiredListeners.add(listener)
  return () => {
    sessionExpiredListeners.delete(listener)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

const isCsrf: Validator<Csrf> = (value): value is Csrf =>
  isRecord(value) && typeof value.headerName === 'string' && typeof value.token === 'string'

export function isArrayOf<T>(itemValidator: Validator<T>): Validator<T[]> {
  return (value): value is T[] => Array.isArray(value) && value.every(itemValidator)
}

export const isNull: Validator<null> = (value): value is null => value === null

async function parse<T>(response: Response, validate: Validator<T>): Promise<T> {
  const body: unknown = await response.json().catch(() => null)
  if (!isRecord(body) || typeof body.code !== 'number' || typeof body.message !== 'string') {
    throw new ApiError('服务返回了无法识别的响应', response.status, response.status)
  }
  const envelope: Envelope<unknown> = {
    code: body.code,
    message: body.message,
    data: body.data,
  }
  if (!response.ok || envelope.code !== 0)
    throw new ApiError(
      envelope.message,
      response.status,
      envelope.code,
      isRecord(envelope.data) && typeof envelope.data.field === 'string'
        ? envelope.data.field
        : undefined,
    )
  if (!validate(envelope.data)) throw new Error('服务返回的数据格式不正确')
  return envelope.data
}

export function refreshCsrf(): Promise<void> {
  if (csrfRequest) return csrfRequest
  const version = sessionVersion
  const pending = (async () => {
    const response = await fetch('/api/auth/csrf', {
      credentials: 'same-origin',
      cache: 'no-store',
    })
    const token = await parse(response, isCsrf)
    if (version === sessionVersion) csrf = token
  })().finally(() => {
    if (csrfRequest === pending) csrfRequest = null
  })
  csrfRequest = pending
  return pending
}

export async function request<T>(
  path: string,
  validate: Validator<T>,
  options: RequestInit = {},
): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const needsCsrf = !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)
  const version = sessionVersion

  async function send(): Promise<T> {
    try {
      if (needsCsrf && !csrf) await refreshCsrf()
      // 旧页面的请求不能在重新登录后携带新会话继续提交。
      if (version !== sessionVersion) throw new Error('会话已变更，请重试')
      const headers = new Headers(options.headers)
      if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type'))
        headers.set('Content-Type', 'application/json')
      if (needsCsrf && csrf) headers.set(csrf.headerName, csrf.token)
      const response = await fetch(`/api${path}`, {
        ...options,
        method,
        headers,
        credentials: 'same-origin',
        cache: 'no-store',
      })
      return await parse(response, validate)
    } catch (cause) {
      if (
        cause instanceof ApiError &&
        cause.status === 401 &&
        path !== '/auth/login' &&
        version === sessionVersion
      ) {
        clearCsrf()
        for (const listener of sessionExpiredListeners) listener()
      }
      throw cause
    }
  }

  try {
    return await send()
  } catch (cause) {
    // 仅重试过滤器明确拒绝的 CSRF 请求，业务 403 和网络失败不重放。
    if (
      needsCsrf &&
      cause instanceof ApiError &&
      cause.status === 403 &&
      cause.code === 40301 &&
      version === sessionVersion
    ) {
      csrf = null
      await refreshCsrf()
      return send()
    }
    throw cause
  }
}
