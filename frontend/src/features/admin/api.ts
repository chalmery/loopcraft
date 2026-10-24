import { isPositiveInteger, isRecord, isPageOf } from '@/lib/contracts'
import { isNull, request, type Validator } from '@/lib/api'
import { queryString } from '@/lib/utils'
import { isUser, type User } from '@/features/auth/api'
import { isTenant } from '@/features/workspace/api'

export type ManagedTenant = {
  id: number
  name: string
  ownerUserId: number
  ownerUsername: string
  ownerDisplayName: string
  updatedAt: string
}
export type UserInput = { username: string; displayName: string; enabled: boolean }
export type TenantInput = { name: string; ownerUserId: number }

const isManagedTenant: Validator<ManagedTenant> = (value): value is ManagedTenant =>
  isRecord(value) &&
  isPositiveInteger(value.id) &&
  typeof value.name === 'string' &&
  isPositiveInteger(value.ownerUserId) &&
  typeof value.ownerUsername === 'string' &&
  typeof value.ownerDisplayName === 'string' &&
  typeof value.updatedAt === 'string'

export const adminApi = {
  users: (page = 1, size = 10) =>
    request(`/admin/users${queryString({ page, size })}`, isPageOf(isUser)),
  tenants: (page = 1, size = 10) =>
    request(`/admin/tenants${queryString({ page, size })}`, isPageOf(isManagedTenant)),
  createUser: (username: string, password: string, displayName: string) =>
    request('/admin/users', isUser, {
      method: 'POST',
      body: JSON.stringify({ username, password, displayName }),
    }),
  updateUser: (id: number, input: UserInput) =>
    request(`/admin/users/${id}`, isUser, { method: 'PATCH', body: JSON.stringify(input) }),
  deleteUser: (id: number) => request(`/admin/users/${id}`, isNull, { method: 'DELETE' }),
  createTenant: (input: TenantInput) =>
    request('/admin/tenants', isTenant, { method: 'POST', body: JSON.stringify(input) }),
  updateTenant: (id: number, input: TenantInput) =>
    request(`/admin/tenants/${id}`, isTenant, { method: 'PATCH', body: JSON.stringify(input) }),
  deleteTenant: (id: number) => request(`/admin/tenants/${id}`, isNull, { method: 'DELETE' }),
  async ownerOptions(): Promise<User[]> {
    const users: User[] = []
    let page = 1
    while (true) {
      const result = await adminApi.users(page, 100)
      users.push(...result.records)
      if (result.records.length === 0 || page * result.size >= result.total)
        return users.filter((user) => user.enabled)
      page += 1
    }
  },
}

export type { Page as PageResult } from '@/lib/contracts'
