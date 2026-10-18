'use client'

import { useEffect, useState } from 'react'
import { Building2, Cpu, PanelLeftClose, PanelLeftOpen, PanelsTopLeft, Users } from 'lucide-react'
import { toast } from 'sonner'
import { LoopCraftMark } from '@/components/layout/LoopCraftMark'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { cn } from '@/lib/utils'
import { onSessionExpired } from '@/lib/api'
import { authApi, type User } from '@/features/auth/api'
import { Login } from '@/features/auth/Login'
import { TenantManagement } from '@/features/admin/TenantManagement'
import { UserManagement } from '@/features/admin/UserManagement'
import { ProfileSection } from '@/features/profile/ProfileSection'
import { AccountMenu } from '@/features/profile/AccountMenu'
import { profileApi } from '@/features/profile/api'
import { TenantSelection } from '@/features/workspace/TenantSelection'
import { LlmAccess } from '@/features/llm-access/LlmAccess'
import { WorkspaceSection } from '@/features/workspace/WorkspaceSection'
import { workspaceApi } from '@/features/workspace/api'
import { useWorkspaceData } from '@/features/workspace/useWorkspaceData'

type Section = 'llm' | 'workspace' | 'profile' | 'tenants' | 'users'
const titles: Record<Section, string> = {
  llm: '模型接入',
  workspace: '工作区与成员',
  profile: '个人设置',
  tenants: '租户管理',
  users: '账号管理',
}

export function IdentityWorkspace({ initialUser }: { initialUser: User | null }) {
  const [user, setUser] = useState(initialUser)
  useEffect(() => onSessionExpired(() => setUser(null)), [])
  async function login(username: string, password: string) {
    setUser(await authApi.login(username, password))
  }
  return user ? (
    <AuthenticatedPortal key={user.id} user={user} onUser={setUser} />
  ) : (
    <Login onLogin={login} />
  )
}

function AuthenticatedPortal({
  user,
  onUser,
}: {
  user: User
  onUser: (user: User | null) => void
}) {
  const [section, setSection] = useState<Section>('profile')
  const [screen, setScreen] = useState<'selection' | 'settings'>('selection')
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [busy, setBusy] = useState(false)
  const workspace = useWorkspaceData(user.id)
  const { tenants, tenant, tenantId, currentMembers, loading, error, setError } = workspace
  const isOwner = tenant?.myRole === 'OWNER'
  const visibleSection: Section =
    (section === 'workspace' && !isOwner) ||
    ((section === 'tenants' || section === 'users') && !user.platformAdmin)
      ? 'profile'
      : section
  const links = [
    ...(tenant ? [{ id: 'llm' as const, label: titles.llm, icon: Cpu }] : []),
    ...(isOwner
      ? [{ id: 'workspace' as const, label: titles.workspace, icon: PanelsTopLeft }]
      : []),
    ...(user.platformAdmin
      ? [
          { id: 'tenants' as const, label: titles.tenants, icon: Building2 },
          { id: 'users' as const, label: titles.users, icon: Users },
        ]
      : []),
  ]

  async function run(action: () => Promise<void>, success: string) {
    setBusy(true)
    try {
      await action()
      toast.success(success)
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '操作失败，请稍后重试')
    } finally {
      setBusy(false)
    }
  }
  function refreshTenants() {
    void workspace
      .reload()
      .catch((cause: unknown) => setError(cause instanceof Error ? cause.message : '刷新租户失败'))
  }
  function profile() {
    setScreen('settings')
    setSection('profile')
  }
  function selectTenant(id: number) {
    workspace.setTenantId(id)
    setSection(tenants.find((item) => item.id === id)?.myRole === 'OWNER' ? 'workspace' : 'llm')
    setScreen('settings')
    setError('')
  }
  function switchTenant(value: string) {
    const next = tenants.find((item) => item.id === Number(value))
    if (!next || next.id === tenantId) return
    selectTenant(next.id)
    toast.success(`已切换至 ${next.name}`)
  }
  async function renameTenant(name: string): Promise<boolean> {
    if (tenantId === null) return false
    setBusy(true)
    try {
      const updated = await workspaceApi.renameTenant(tenantId, name)
      workspace.setTenants((items) =>
        items.map((item) => (item.id === updated.id ? { ...item, ...updated } : item)),
      )
      toast.success('工作区名称已更新')
      return true
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '更新工作区失败')
      return false
    } finally {
      setBusy(false)
    }
  }
  const account = (
    <AccountMenu
      user={user}
      busy={busy}
      onProfile={profile}
      onLogout={() =>
        void run(async () => {
          await authApi.logout()
          onUser(null)
        }, '已退出登录')
      }
    />
  )
  const feedback = error && (
    <div
      role="alert"
      className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-md bg-danger-soft px-3 py-2 text-sm text-danger"
    >
      <span>{error}</span>
      <Button variant="ghost" size="sm" onClick={refreshTenants}>
        重试
      </Button>
    </div>
  )

  if (screen === 'selection')
    return (
      <main className="min-h-screen">
        <header className="flex h-12 items-center justify-between border-b border-border bg-surface px-4 md:px-5">
          <div className="flex items-center gap-2 font-semibold">
            <LoopCraftMark className="size-8" />
            LoopCraft
          </div>
          <div className="flex items-center gap-3">
            {user.platformAdmin && (
              <Button
                variant="outline"
                onClick={() => {
                  setSection('tenants')
                  setScreen('settings')
                }}
              >
                后台配置
              </Button>
            )}
            {account}
          </div>
        </header>
        {feedback && <div className="mx-auto mt-4 max-w-lg px-4">{feedback}</div>}
        {loading ? (
          <p className="py-12 text-center text-muted-foreground">正在加载租户…</p>
        ) : (
          <TenantSelection tenants={tenants} onSelect={(selected) => selectTenant(selected.id)} />
        )}
      </main>
    )

  return (
    <div
      className={cn(
        'grid min-h-screen grid-cols-1',
        sidebarCollapsed
          ? 'md:grid-cols-[64px_minmax(0,1fr)]'
          : 'md:grid-cols-[208px_minmax(0,1fr)] xl:grid-cols-[224px_minmax(0,1fr)]',
      )}
    >
      <aside
        className="flex flex-col border-b border-border bg-surface px-0 py-2 md:min-h-screen md:border-r md:border-b-0 md:py-3"
        aria-label="主导航"
      >
        <div
          className={cn(
            'flex items-center gap-2 px-3 text-sm font-semibold',
            sidebarCollapsed && 'md:justify-center md:px-0',
          )}
        >
          <div
            className={cn(
              'flex min-w-0 flex-1 items-center gap-2',
              sidebarCollapsed && 'md:hidden',
            )}
          >
            <LoopCraftMark />
            <span className="truncate">LoopCraft</span>
          </div>
          <Button
            variant="ghost"
            size="icon-sm"
            className="hidden md:inline-flex md:shrink-0"
            aria-label={sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'}
            title={sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'}
            onClick={() => setSidebarCollapsed((collapsed) => !collapsed)}
          >
            {sidebarCollapsed ? (
              <PanelLeftOpen aria-hidden="true" />
            ) : (
              <PanelLeftClose aria-hidden="true" />
            )}
          </Button>
        </div>
        {links.length > 0 && (
          <>
            <div
              className={cn(
                'mx-3 mt-4 mb-1.5 text-xs font-medium text-muted-foreground',
                sidebarCollapsed && 'md:hidden',
              )}
            >
              后台配置
            </div>
            <nav className="flex flex-col gap-1" aria-label="后台配置">
              {links.map(({ id, label, icon: Icon }) => (
                <Button
                  key={id}
                  variant="ghost"
                  className={cn(
                    'w-full justify-start rounded-none px-3 text-secondary',
                    sidebarCollapsed && 'md:justify-center md:px-0',
                    visibleSection === id &&
                      'bg-accent-soft text-accent hover:enabled:bg-accent-soft',
                  )}
                  aria-current={visibleSection === id ? 'page' : undefined}
                  aria-label={sidebarCollapsed ? label : undefined}
                  title={sidebarCollapsed ? label : undefined}
                  onClick={() => setSection(id)}
                >
                  <Icon className="size-4" aria-hidden="true" />
                  <span className={cn(sidebarCollapsed && 'md:hidden')}>{label}</span>
                </Button>
              ))}
            </nav>
          </>
        )}
      </aside>
      <main className="flex min-h-screen min-w-0 flex-col">
        <header className="flex h-12 items-center justify-between gap-2 border-b border-border bg-surface px-3 md:px-5">
          <div className="min-w-0 truncate text-sm font-medium">{titles[visibleSection]}</div>
          <div className="flex shrink-0 items-center gap-1 sm:gap-2">
            <Select
              value={tenant ? String(tenant.id) : ''}
              disabled={busy || loading || tenants.length === 0}
              onValueChange={switchTenant}
            >
              <SelectTrigger aria-label="切换租户" variant="ghost" className="w-28 sm:w-48">
                <Building2 className="size-4 text-muted-foreground" aria-hidden="true" />
                <SelectValue placeholder="选择租户" />
              </SelectTrigger>
              <SelectContent align="end" className="w-64">
                <SelectGroup>
                  <SelectLabel>切换租户</SelectLabel>
                  {tenants.map((item) => (
                    <SelectItem key={item.id} value={String(item.id)} title={item.name}>
                      {item.name}
                    </SelectItem>
                  ))}
                </SelectGroup>
              </SelectContent>
            </Select>
            {account}
          </div>
        </header>
        <section className="mx-auto w-full max-w-[1280px] px-4 py-4 md:px-5 md:py-5 xl:px-6">
          {feedback}
          {visibleSection === 'tenants' && user.platformAdmin && (
            <TenantManagement onChanged={refreshTenants} />
          )}
          {visibleSection === 'users' && user.platformAdmin && (
            <UserManagement
              onUserUpdated={(updated) => {
                if (updated.id === user.id) {
                  if (updated.username !== user.username) {
                    onUser(null)
                    toast.success('用户名已变更，请重新登录')
                  } else onUser(updated)
                }
              }}
            />
          )}
          {visibleSection === 'workspace' && isOwner && (
            <WorkspaceSection
              key={tenantId}
              tenant={tenant}
              tenants={tenants}
              members={currentMembers}
              user={user}
              canManageMembers={isOwner}
              busy={busy}
              onRename={renameTenant}
              onAddMember={(id) =>
                run(async () => {
                  if (tenantId !== null) {
                    await workspaceApi.addMember(tenantId, id)
                    await workspace.reload()
                  }
                }, '成员已添加')
              }
              onRemoveMember={(id) =>
                run(async () => {
                  if (tenantId !== null) {
                    await workspaceApi.removeMember(tenantId, id)
                    await workspace.reload()
                  }
                }, '成员已移除')
              }
            />
          )}
          {visibleSection === 'llm' && tenantId !== null && (
            <LlmAccess
              key={tenantId}
              tenantId={tenantId}
              canManage={isOwner || user.platformAdmin}
            />
          )}
          {visibleSection === 'profile' && (
            <ProfileSection
              user={user}
              busy={busy}
              onRename={(name) =>
                run(async () => onUser(await profileApi.rename(name)), '显示名称已更新')
              }
              onChangePassword={(oldPassword, newPassword) =>
                run(async () => {
                  await profileApi.changePassword(oldPassword, newPassword)
                  onUser(null)
                }, '密码已更新，请重新登录')
              }
            />
          )}
        </section>
      </main>
    </div>
  )
}
