import { headers } from 'next/headers'
import { IdentityWorkspace } from '@/features/portal/IdentityWorkspace'
import { isUser, type User } from '@/features/auth/api'
import { BACKEND_URL } from '@/lib/backend'

export const dynamic = 'force-dynamic'

async function getCurrentUser(): Promise<User | null> {
  const cookie = (await headers()).get('cookie')
  if (!cookie) return null
  try {
    const response = await fetch(`${BACKEND_URL}/api/users/me`, {
      headers: { cookie },
      cache: 'no-store',
    })
    if (!response.ok) return null
    const body: unknown = await response.json()
    if (typeof body !== 'object' || body === null || !('data' in body)) return null
    return isUser(body.data) ? body.data : null
  } catch {
    return null
  }
}

export default async function Home() {
  return <IdentityWorkspace initialUser={await getCurrentUser()} />
}
