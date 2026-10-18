import { isArrayOf, isNull, request, type Validator } from '@/lib/api'
import type { User } from '@/features/auth/api'

export type Tenant = {
  id: number
  name: string
  updatedAt: string
  myRole?: 'OWNER' | 'MEMBER' | null
}
export type Member = {
  userId: number
  username: string
  displayName: string
  role: 'OWNER' | 'MEMBER'
  enabled: boolean
}
export type MemberCandidate = Pick<User, 'id' | 'username' | 'displayName'>

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export const isTenant: Validator<Tenant> = (value): value is Tenant =>
  isRecord(value) &&
  typeof value.id === 'number' &&
  typeof value.name === 'string' &&
  typeof value.updatedAt === 'string' &&
  (value.myRole === undefined ||
    value.myRole === null ||
    value.myRole === 'OWNER' ||
    value.myRole === 'MEMBER')

const isMember: Validator<Member> = (value): value is Member =>
  isRecord(value) &&
  typeof value.userId === 'number' &&
  typeof value.username === 'string' &&
  typeof value.displayName === 'string' &&
  (value.role === 'OWNER' || value.role === 'MEMBER') &&
  typeof value.enabled === 'boolean'

const isMemberCandidate: Validator<MemberCandidate> = (value): value is MemberCandidate =>
  isRecord(value) &&
  typeof value.id === 'number' &&
  typeof value.username === 'string' &&
  typeof value.displayName === 'string'

export const workspaceApi = {
  async tenants(): Promise<Tenant[]> {
    const tenants: Tenant[] = []
    for (let page = 1; ; page += 1) {
      const batch = await request(`/tenants?page=${page}&size=100`, isArrayOf(isTenant))
      tenants.push(...batch)
      if (batch.length < 100) return tenants
    }
  },
  async members(tenantId: number): Promise<Member[]> {
    const members: Member[] = []
    for (let page = 1; ; page += 1) {
      const batch = await request(
        `/tenants/${tenantId}/members?page=${page}&size=100`,
        isArrayOf(isMember),
      )
      members.push(...batch)
      if (batch.length < 100) return members
    }
  },
  async memberCandidates(tenantId: number): Promise<MemberCandidate[]> {
    const candidates: MemberCandidate[] = []
    for (let page = 1; ; page += 1) {
      const batch = await request(
        `/tenants/${tenantId}/member-candidates?page=${page}&size=100`,
        isArrayOf(isMemberCandidate),
      )
      candidates.push(...batch)
      if (batch.length < 100) return candidates
    }
  },
  renameTenant: (tenantId: number, name: string) =>
    request(`/tenants/${tenantId}`, isTenant, {
      method: 'PATCH',
      body: JSON.stringify({ name }),
    }),
  addMember: (tenantId: number, userId: number) =>
    request(`/tenants/${tenantId}/members`, isNull, {
      method: 'POST',
      body: JSON.stringify({ userId }),
    }),
  removeMember: (tenantId: number, userId: number) =>
    request(`/tenants/${tenantId}/members/${userId}`, isNull, { method: 'DELETE' }),
}

export type { User }
