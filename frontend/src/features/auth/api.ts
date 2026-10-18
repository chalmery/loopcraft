import { clearCsrf, isNull, refreshCsrf, request, type Validator } from '@/lib/api'

export type User = {
  id: number
  username: string
  displayName: string
  platformAdmin: boolean
  enabled: boolean
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export const isUser: Validator<User> = (value): value is User =>
  isRecord(value) &&
  typeof value.id === 'number' &&
  typeof value.username === 'string' &&
  typeof value.displayName === 'string' &&
  typeof value.platformAdmin === 'boolean' &&
  typeof value.enabled === 'boolean'

export const authApi = {
  async login(username: string, password: string): Promise<User> {
    clearCsrf()
    await refreshCsrf()
    const user = await request('/auth/login', isUser, {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    })
    clearCsrf()
    await refreshCsrf()
    return user
  },
  me: () => request('/users/me', isUser),
  async logout(): Promise<void> {
    await request('/auth/logout', isNull, { method: 'POST' })
    clearCsrf()
  },
}
