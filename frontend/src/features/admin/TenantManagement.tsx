import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import type { User } from '@/features/auth/api'
import { adminApi, type ManagedTenant } from './api'
import { useAdminPage } from './useAdminPage'
import { DeleteRecord, EditorSheet, ListHeading, ListPagination } from './AdminListParts'

export function TenantManagement({ onChanged }: { onChanged: () => void }) {
  const list = useAdminPage(adminApi.tenants)
  const [editor, setEditor] = useState<ManagedTenant | null | undefined>()
  function changed() {
    list.reload()
    onChanged()
  }
  return (
    <div>
      <ListHeading
        title="租户管理"
        description="管理平台租户及负责人。"
        pending={list.pending}
        onAdd={() => setEditor(null)}
        onReload={list.reload}
      />
      {list.error && (
        <p role="alert" className="mb-4 text-sm text-danger">
          {list.error}
        </p>
      )}
      <div
        className="overflow-hidden rounded-lg border border-border bg-surface"
        aria-busy={list.pending}
      >
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>ID</TableHead>
              <TableHead>租户名称</TableHead>
              <TableHead>负责人</TableHead>
              <TableHead>更新时间</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {list.pending ? (
              <TableRow>
                <TableCell colSpan={5} className="h-32 text-center text-muted-foreground">
                  正在加载租户…
                </TableCell>
              </TableRow>
            ) : list.data?.records.length ? (
              list.data.records.map((tenant) => (
                <TableRow key={tenant.id}>
                  <TableCell className="text-muted-foreground">{tenant.id}</TableCell>
                  <TableCell className="max-w-64 truncate font-medium" title={tenant.name}>
                    {tenant.name}
                  </TableCell>
                  <TableCell>
                    <div className="max-w-64 truncate" title={tenant.ownerDisplayName}>
                      {tenant.ownerDisplayName}
                    </div>
                    <div className="text-xs text-muted-foreground">@{tenant.ownerUsername}</div>
                  </TableCell>
                  <TableCell className="text-muted-foreground">
                    {new Date(tenant.updatedAt).toLocaleString('zh-CN', {
                      timeZone: 'Asia/Shanghai',
                      hour12: false,
                    })}
                  </TableCell>
                  <TableCell>
                    <div className="flex justify-end gap-1">
                      <Button variant="ghost" size="sm" onClick={() => setEditor(tenant)}>
                        编辑
                      </Button>
                      <DeleteRecord
                        name={tenant.name}
                        description="删除后，该租户及其成员关系将被移除。存在模型来源等业务数据时，请先清理相关配置。此操作无法撤销。"
                        onDelete={() => adminApi.deleteTenant(tenant.id)}
                        onDeleted={changed}
                      />
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableRow>
                <TableCell colSpan={5} className="h-32 text-center text-muted-foreground">
                  {list.error ? '加载失败，请刷新重试' : '暂无租户，点击新增创建'}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
        <ListPagination
          page={list.page}
          size={list.size}
          total={list.data?.total ?? 0}
          pending={list.pending}
          onPage={list.setPage}
        />
      </div>
      {editor !== undefined && (
        <TenantEditor
          tenant={editor}
          onClose={() => setEditor(undefined)}
          onSaved={() => {
            setEditor(undefined)
            changed()
          }}
        />
      )}
    </div>
  )
}

function TenantEditor({
  tenant,
  onClose,
  onSaved,
}: {
  tenant: ManagedTenant | null
  onClose: () => void
  onSaved: () => void
}) {
  const [users, setUsers] = useState<User[]>([])
  const [loading, setLoading] = useState(true)
  const [optionsError, setOptionsError] = useState('')
  const [revision, setRevision] = useState(0)
  const [owner, setOwner] = useState(tenant ? String(tenant.ownerUserId) : '')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  useEffect(() => {
    let active = true
    adminApi
      .ownerOptions()
      .then((items) => {
        if (active) {
          setUsers(items)
          setLoading(false)
        }
      })
      .catch((cause: unknown) => {
        if (active) {
          setOptionsError(cause instanceof Error ? cause.message : '加载负责人失败')
          setLoading(false)
        }
      })
    return () => {
      active = false
    }
  }, [revision])
  async function save(data: FormData) {
    setBusy(true)
    setError('')
    try {
      const name = String(data.get('name') ?? '').trim()
      if (!name) throw new Error('请输入租户名称')
      if (!owner || !users.some((user) => user.id === Number(owner)))
        throw new Error('请选择已启用的负责人账号')
      const input = { name, ownerUserId: Number(owner) }
      if (tenant) await adminApi.updateTenant(tenant.id, input)
      else await adminApi.createTenant(input)
      toast.success(tenant ? '租户已更新' : '租户已创建')
      onSaved()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '保存失败')
    } finally {
      setBusy(false)
    }
  }
  return (
    <EditorSheet
      title={tenant ? '编辑租户' : '新增租户'}
      description="设置租户名称，并指定一位负责人。"
      busy={busy}
      onClose={onClose}
    >
      <form
        className="flex min-h-0 flex-1 flex-col"
        onSubmit={(event) => {
          event.preventDefault()
          void save(new FormData(event.currentTarget))
        }}
      >
        <fieldset
          disabled={busy}
          className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto px-6 py-4"
        >
          <Label className="grid gap-2">
            租户名称
            <Input name="name" required maxLength={100} defaultValue={tenant?.name ?? ''} />
          </Label>
          <div className="grid gap-2">
            <Label htmlFor="tenant-owner">负责人</Label>
            <Select
              value={owner}
              onValueChange={setOwner}
              disabled={busy || loading || users.length === 0}
            >
              <SelectTrigger id="tenant-owner" className="w-full">
                <SelectValue placeholder={loading ? '正在加载账号…' : '选择负责人'} />
              </SelectTrigger>
              <SelectContent>
                {users.map((user) => (
                  <SelectItem key={user.id} value={String(user.id)}>
                    {user.displayName} · @{user.username}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          {tenant && (
            <p className="text-xs text-muted-foreground">更换负责人后，原负责人保留为普通成员。</p>
          )}
          {optionsError && (
            <div role="alert" className="text-sm text-danger">
              {optionsError}
              <Button
                variant="link"
                disabled={loading}
                onClick={() => {
                  setLoading(true)
                  setOptionsError('')
                  setRevision((value) => value + 1)
                }}
              >
                重试
              </Button>
            </div>
          )}
          {!loading && !optionsError && users.length === 0 && (
            <p className="text-sm text-muted-foreground">暂无启用的账号，请先到账号管理新增。</p>
          )}
          {error && (
            <p role="alert" className="text-sm text-danger">
              {error}
            </p>
          )}
        </fieldset>
        <div className="flex justify-end gap-2 border-t border-border p-6">
          <Button variant="outline" disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button
            type="submit"
            disabled={
              busy || loading || !!optionsError || !users.some((user) => user.id === Number(owner))
            }
          >
            {busy ? '保存中…' : '保存'}
          </Button>
        </div>
      </form>
    </EditorSheet>
  )
}
