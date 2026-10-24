import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react'
import { workspaceApi, type Member, type Tenant } from './api'

type TenantState = {
  userId: number | undefined
  tenants: Tenant[]
  loading: boolean
  error: string
}
type MemberState = {
  userId: number | undefined
  tenantId: number | null
  members: Member[]
  error: string
}

export function useWorkspaceData(userId: number | undefined) {
  const tenantSequence = useRef(0)
  const memberSequence = useRef(0)
  const [tenantState, setTenantState] = useState<TenantState>(() => ({
    userId,
    tenants: [],
    loading: userId !== undefined,
    error: '',
  }))
  const [memberState, setMemberState] = useState<MemberState>({
    userId,
    tenantId: null,
    members: [],
    error: '',
  })
  const [selection, setSelection] = useState<{
    userId: number | undefined
    tenantId: number | null
  }>({ userId, tenantId: null })

  useEffect(() => {
    if (userId === undefined) return
    const requestId = ++tenantSequence.current
    workspaceApi
      .tenants()
      .then((tenants) => {
        if (requestId === tenantSequence.current)
          setTenantState({ userId, tenants, loading: false, error: '' })
      })
      .catch((cause: unknown) => {
        if (requestId === tenantSequence.current) {
          setTenantState({
            userId,
            tenants: [],
            loading: false,
            error: cause instanceof Error ? cause.message : '加载租户列表失败',
          })
        }
      })
    return () => {
      tenantSequence.current += 1
    }
  }, [userId])

  const tenants = tenantState.userId === userId ? tenantState.tenants : []
  const tenantId = selection.userId === userId ? selection.tenantId : null
  const tenant = tenants.find((item) => item.id === tenantId) ?? null

  useEffect(() => {
    if (userId === undefined || tenantId === null || tenant?.myRole !== 'OWNER') return
    const requestId = ++memberSequence.current
    workspaceApi
      .members(tenantId)
      .then((members) => {
        if (requestId === memberSequence.current)
          setMemberState({ userId, tenantId, members, error: '' })
      })
      .catch((cause: unknown) => {
        if (requestId === memberSequence.current) {
          setMemberState({
            userId,
            tenantId,
            members: [],
            error: cause instanceof Error ? cause.message : '加载成员列表失败',
          })
        }
      })
    return () => {
      memberSequence.current += 1
    }
  }, [userId, tenantId, tenant?.myRole])

  const currentMemberState = memberState.userId === userId && memberState.tenantId === tenantId
  const currentMembers = currentMemberState ? memberState.members : []
  const error = [
    tenantState.userId === userId ? tenantState.error : '',
    currentMemberState ? memberState.error : '',
  ]
    .filter(Boolean)
    .join(' ')

  const setTenants: Dispatch<SetStateAction<Tenant[]>> = (update) => {
    setTenantState((current) => {
      const currentTenants = current.userId === userId ? current.tenants : []
      const nextTenants = typeof update === 'function' ? update(currentTenants) : update
      return { userId, tenants: nextTenants, loading: false, error: current.error }
    })
  }

  function setTenantId(nextTenantId: number | null) {
    memberSequence.current += 1
    setSelection({ userId, tenantId: nextTenantId })
  }

  function setError(message: string) {
    setTenantState((current) => ({
      userId,
      tenants: current.userId === userId ? current.tenants : [],
      loading: current.userId === userId ? current.loading : userId !== undefined,
      error: message,
    }))
    setMemberState((current) => ({
      ...current,
      error: current.userId === userId && current.tenantId === tenantId ? message : current.error,
    }))
  }

  async function reload() {
    if (userId === undefined) return
    const tenantRequest = ++tenantSequence.current
    const memberRequest = ++memberSequence.current
    try {
      const nextTenants = await workspaceApi.tenants()
      if (tenantRequest !== tenantSequence.current) return
      setTenantState({ userId, tenants: nextTenants, loading: false, error: '' })
      if (memberRequest !== memberSequence.current) return
      if (tenantId !== null && !nextTenants.some((item) => item.id === tenantId)) {
        setSelection({ userId, tenantId: null })
      }
      if (
        tenantId !== null &&
        nextTenants.find((item) => item.id === tenantId)?.myRole === 'OWNER'
      ) {
        const members = await workspaceApi.members(tenantId)
        if (tenantRequest !== tenantSequence.current || memberRequest !== memberSequence.current)
          return
        setMemberState({ userId, tenantId, members, error: '' })
      }
    } catch (cause) {
      if (tenantRequest === tenantSequence.current && memberRequest === memberSequence.current)
        throw cause
    }
  }

  return {
    tenants,
    setTenants,
    tenantId,
    setTenantId,
    tenant,
    currentMembers,
    loading: tenantState.userId === userId ? tenantState.loading : userId !== undefined,
    error,
    setError,
    reload,
  }
}
