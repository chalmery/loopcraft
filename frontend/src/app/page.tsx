import { headers } from 'next/headers'
import { IdentityWorkspace } from '@/features/portal/IdentityWorkspace'
import { isUser, type User } from '@/features/auth/api'

export const dynamic = 'force-dynamic'

async function getCurrentUser(): Promise<User | null> {
  const cookie = (await headers()).get('cookie')
  if (!cookie) return null
  const backendUrl = process.env.BACKEND_URL || 'http://127.0.0.1:8080'
  try {
    const response = await fetch(`${backendUrl}/api/users/me`, {
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
