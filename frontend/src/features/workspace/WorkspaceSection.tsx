import { useEffect, useState } from 'react'
import { PanelsTopLeft } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import type { Member, MemberCandidate, Tenant } from './api'
import { workspaceApi } from './api'
import type { User } from '@/features/auth/api'

export function WorkspaceSection({
  tenant,
  tenants,
  members,
  user,
  canManageMembers,
  busy,
  onRename,
  onAddMember,
  onRemoveMember,
}: {
  tenant: Tenant | null
  tenants: Tenant[]
  members: Member[]
  user: User
  canManageMembers: boolean
  busy: boolean
  onRename: (name: string) => Promise<boolean>
  onAddMember: (id: number) => Promise<void>
  onRemoveMember: (id: number) => Promise<void>
}) {
  const [editing, setEditing] = useState(false)
  if (!tenant)
    return (
      <div className="flex min-h-[55vh] flex-col items-center justify-center text-center [&_h1]:mb-2 [&_h1]:text-2xl [&_h1]:font-semibold [&_p]:max-w-[440px] [&_p]:text-secondary">
        <span className="mb-4 inline-flex size-12 items-center justify-center rounded-lg bg-accent-soft text-accent">
          <PanelsTopLeft className="size-4" aria-hidden="true" />
        </span>
        <h1>{tenants.length ? '选择一个工作区' : '还没有工作区'}</h1>
        <p>
          {tenants.length
            ? '从顶部选择工作区，查看成员与设置。'
            : '请联系平台管理员为你分配工作区。'}
        </p>
      </div>
    )
  const owner = tenant.myRole === 'OWNER'
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-start justify-between gap-4 pb-2 [&_h1]:text-xl [&_h1]:font-semibold [&_p:not(.eyebrow)]:text-secondary">
        <div>
          <h1>{tenant.name}</h1>
          <p className="mt-1">工作区与成员</p>
        </div>
        <Badge>{owner ? '负责人' : '成员'}</Badge>
      </div>
      <Card className="flex-col items-start justify-between gap-4 p-4 md:p-4 lg:flex-row lg:items-center [&_h2]:text-sm [&_h2]:font-semibold [&>div]:flex [&>div]:flex-col [&>div]:gap-2">
        <div>
          <h2>工作区资料</h2>
          <p className="text-sm text-muted-foreground">工作区名称对所有成员可见。</p>
        </div>
        {owner ? (
          editing ? (
            <form
              className="flex w-full flex-wrap items-center gap-2 lg:w-auto lg:flex-nowrap [&_input]:w-full lg:[&_input]:w-[min(260px,24vw)]"
              onSubmit={(event) => {
                event.preventDefault()
                const data = new FormData(event.currentTarget)
                void onRename(String(data.get('name') ?? '')).then((saved) => {
                  if (saved) setEditing(false)
                })
              }}
            >
              <Input
                aria-label="工作区名称"
                name="name"
                defaultValue={tenant.name}
                maxLength={100}
                required
              />
              <Button variant="default" type="submit" disabled={busy}>
                保存
              </Button>
              <Button variant="outline" type="button" onClick={() => setEditing(false)}>
                取消
              </Button>
            </form>
          ) : (
            <Button variant="outline" type="button" onClick={() => setEditing(true)}>
              修改名称
            </Button>
          )
        ) : (
          <span className="text-xs text-muted-foreground">仅负责人可修改</span>
        )}
      </Card>
      <Card className="gap-2 p-4 md:p-4 [&_h2]:text-sm [&_h2]:font-semibold">
        <div className="flex flex-col items-start justify-between gap-3 pb-1 lg:flex-row lg:items-center [&>div]:flex [&>div]:flex-col [&>div]:gap-2">
          <div>
            <h2>成员</h2>
            <p className="text-sm text-muted-foreground">
              {members.length} 位成员可以访问此工作区。
            </p>
          </div>
          {canManageMembers && (
            <AddMemberForm tenantId={tenant.id} members={members} busy={busy} onAdd={onAddMember} />
          )}
        </div>
        <ul className="flex flex-col">
          {members.map((member) => (
            <li
              className="flex min-h-16 flex-wrap items-center gap-2 border-t border-border py-2 md:gap-3"
              key={member.userId}
            >
              <span className="inline-flex size-8 shrink-0 items-center justify-center rounded-md bg-surface-subtle text-sm font-semibold text-secondary">
                {member.displayName.slice(0, 1)}
              </span>
              <div className="flex min-w-0 flex-1 flex-col gap-1 [&_small]:ml-2 [&_small]:text-xs [&_small]:font-normal [&_small]:text-muted-foreground [&_span]:text-xs [&_span]:text-muted-foreground [&_strong]:truncate [&_strong]:text-sm [&_strong]:font-medium">
                <strong>
                  {member.displayName}
                  {member.userId === user.id && <small>你</small>}
                </strong>
                <span>@{member.username}</span>
              </div>
              <Badge variant={member.role === 'OWNER' ? 'default' : 'secondary'}>
                {member.role === 'OWNER' ? '负责人' : '成员'}
              </Badge>
              {canManageMembers && member.role !== 'OWNER' && (
                <RemoveMemberButton member={member} busy={busy} onRemove={onRemoveMember} />
              )}
            </li>
          ))}
        </ul>
        {!canManageMembers && (
          <p className="mt-2 text-xs text-muted-foreground">只有工作区负责人可以管理成员。</p>
        )}
      </Card>
    </div>
  )
}

function AddMemberForm({
  tenantId,
  members,
  busy,
  onAdd,
}: {
  tenantId: number
  members: Member[]
  busy: boolean
  onAdd: (id: number) => Promise<void>
}) {
  const [candidates, setCandidates] = useState<MemberCandidate[]>([])
  const [selectedId, setSelectedId] = useState('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    workspaceApi
      .memberCandidates(tenantId)
      .then((items) => {
        if (active) {
          setCandidates(items)
          setSelectedId('')
          setLoading(false)
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setError(cause instanceof Error ? cause.message : '加载可添加账号失败')
          setLoading(false)
        }
      })
    return () => {
      active = false
    }
  }, [tenantId, members, revision])
  const available = candidates.filter(
    (candidate) => !members.some((member) => member.userId === candidate.id),
  )

  return (
    <div className="flex w-full min-w-0 flex-col gap-2 lg:w-auto">
      <form
        className="flex w-full min-w-0 items-center gap-2 lg:w-auto"
        onSubmit={(event) => {
          event.preventDefault()
          const id = Number(selectedId)
          if (Number.isSafeInteger(id) && id > 0) void onAdd(id)
        }}
      >
        <Select
          value={selectedId}
          onValueChange={setSelectedId}
          disabled={busy || loading || available.length === 0}
        >
          <SelectTrigger aria-label="选择要添加的成员" className="w-full lg:w-64">
            <SelectValue
              placeholder={
                loading ? '正在加载账号…' : available.length ? '选择账号' : '没有可添加的账号'
              }
            />
          </SelectTrigger>
          <SelectContent>
            {available.map((candidate) => (
              <SelectItem key={candidate.id} value={String(candidate.id)}>
                {candidate.displayName} · @{candidate.username}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button variant="outline" type="submit" disabled={busy || loading || !selectedId}>
          添加成员
        </Button>
      </form>
      {error && (
        <div role="alert" className="flex items-center gap-2 text-xs text-danger">
          <span>{error}</span>
          <Button
            variant="link"
            size="sm"
            disabled={loading}
            onClick={() => {
              setLoading(true)
              setError('')
              setRevision((value) => value + 1)
            }}
          >
            重试
          </Button>
        </div>
      )}
    </div>
  )
}

function RemoveMemberButton({
  member,
  busy,
  onRemove,
}: {
  member: Member
  busy: boolean
  onRemove: (id: number) => Promise<void>
}) {
  return (
    <AlertDialog>
      <AlertDialogTrigger asChild>
        <Button variant="danger-ghost" size="sm" disabled={busy}>
          移除
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>移除成员</AlertDialogTitle>
          <AlertDialogDescription>
            确定从工作区移除 {member.displayName}？移除后，该成员将无法访问此工作区。
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>取消</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={busy}
            onClick={() => void onRemove(member.userId)}
          >
            确认移除
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
