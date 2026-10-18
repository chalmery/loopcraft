import { isNull, request } from '@/lib/api'
import { isUser, type User } from '@/features/auth/api'

export const profileApi = {
  rename: (name: string) =>
    request('/users/me', isUser, { method: 'PATCH', body: JSON.stringify({ name }) }),
  async changePassword(oldPassword: string, newPassword: string): Promise<void> {
    await request('/users/me/password', isNull, {
      method: 'POST',
      body: JSON.stringify({ oldPassword, newPassword }),
    })
  },
}

export type { User }
