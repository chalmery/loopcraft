import { useEffect, useState } from 'react'
import { Plus, RefreshCw } from 'lucide-react'
import { Form } from '@/components/ui/form'
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
import { DeleteRecord } from '@/components/DeleteRecord'
import { EditorSheet } from '@/components/EditorSheet'
import { ErrorState } from '@/components/ErrorState'
import { ListPagination } from '@/components/ListPagination'
import { PageHeader } from '@/components/PageHeader'
import { errorMessage } from '@/lib/errors'
import { usePagedList } from '@/lib/usePagedList'
import { useSubmitting } from '@/lib/useSubmitting'
import type { User } from '@/features/auth/api'
import { adminApi, type ManagedTenant } from './api'

const PAGE_SIZE = 10

export function TenantManagement({ onChanged }: { onChanged: () => void }) {
  const list = usePagedList((page: number) => adminApi.tenants(page, PAGE_SIZE), [])
  const [editor, setEditor] = useState<ManagedTenant | null | undefined>()
  function changed() {
    list.reload()
    onChanged()
  }
  return (
    <div>
      <PageHeader
        title="租户管理"
        description="管理平台租户及负责人。"
        actions={
          <>
            <Button onClick={() => setEditor(null)}>
              <Plus aria-hidden="true" />
              新增
            </Button>
            <Button variant="outline" disabled={list.loading} onClick={list.reload}>
              <RefreshCw aria-hidden="true" />
              刷新
            </Button>
          </>
        }
      />
      <div className="mt-6">
        {list.error && <ErrorState className="mb-4" message={list.error} onRetry={list.reload} />}
        <div
          className="overflow-hidden rounded-lg border border-border bg-surface"
          aria-busy={list.loading}
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
              {list.loading ? (
                <TableRow>
                  <TableCell colSpan={5} className="h-32 text-center text-muted-foreground">
                    <output>正在加载租户…</output>
                  </TableCell>
                </TableRow>
              ) : !list.error && list.data?.records.length ? (
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
                    {list.error ? '加载失败，请重试' : '暂无租户，点击新增创建'}
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
          <ListPagination
            page={list.page}
            size={PAGE_SIZE}
            total={list.data?.total ?? 0}
            pending={list.loading}
            onPage={list.setPage}
          />
        </div>
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
  const submit = useSubmitting()
  const [users, setUsers] = useState<User[]>([])
  const [loading, setLoading] = useState(true)
  const [optionsError, setOptionsError] = useState('')
  const [revision, setRevision] = useState(0)
  const [owner, setOwner] = useState(tenant ? String(tenant.ownerUserId) : '')
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
          setOptionsError(errorMessage(cause, '加载负责人失败'))
          setLoading(false)
        }
      })
    return () => {
      active = false
    }
  }, [revision])
  async function save(data: FormData) {
    await submit.run(async () => {
      const name = String(data.get('name') ?? '').trim()
      if (!name) throw new Error('请输入租户名称')
      if (!owner || !users.some((user) => user.id === Number(owner)))
        throw new Error('请选择已启用的负责人账号')
      const input = { name, ownerUserId: Number(owner) }
      if (tenant) await adminApi.updateTenant(tenant.id, input)
      else await adminApi.createTenant(input)
      onSaved()
      return tenant ? '租户已更新' : '租户已创建'
    }, '保存失败')
  }
  return (
    <EditorSheet
      title={tenant ? '编辑租户' : '新增租户'}
      description="设置租户名称，并指定一位负责人。"
      busy={submit.busy}
      onClose={onClose}
    >
      <Form
        className="flex min-h-0 flex-1 flex-col"
        onSubmit={(event) => {
          event.preventDefault()
          void save(new FormData(event.currentTarget))
        }}
      >
        <fieldset
          disabled={submit.busy}
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
              disabled={submit.busy || loading || users.length === 0}
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
            <ErrorState
              message={optionsError}
              onRetry={() => {
                setLoading(true)
                setOptionsError('')
                setRevision((value) => value + 1)
              }}
            />
          )}
          {!loading && !optionsError && users.length === 0 && (
            <p className="text-sm text-muted-foreground">暂无启用的账号，请先到账号管理新增。</p>
          )}
        </fieldset>
        <div className="flex justify-end gap-2 border-t border-border p-6">
          <Button variant="outline" disabled={submit.busy} onClick={onClose}>
            取消
          </Button>
          <Button
            type="submit"
            loading={submit.busy}
            disabled={
              submit.busy ||
              loading ||
              !!optionsError ||
              !users.some((user) => user.id === Number(owner))
            }
          >
            {submit.busy ? '保存中…' : '保存'}
          </Button>
        </div>
      </Form>
    </EditorSheet>
  )
}
