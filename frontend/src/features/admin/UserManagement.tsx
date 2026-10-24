import { useState } from 'react'
import { Plus, RefreshCw } from 'lucide-react'
import { Form } from '@/components/ui/form'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
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
import { usePagedList } from '@/lib/usePagedList'
import { useSubmitting } from '@/lib/useSubmitting'
import type { User } from '@/features/auth/api'
import { adminApi } from './api'

const PAGE_SIZE = 10

export function UserManagement({ onUserUpdated }: { onUserUpdated: (user: User) => void }) {
  const list = usePagedList((page: number) => adminApi.users(page, PAGE_SIZE), [])
  const [editor, setEditor] = useState<User | null | undefined>()
  return (
    <div>
      <PageHeader
        title="账号管理"
        description="管理平台账号、基本资料与启用状态。"
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
                <TableHead>用户名</TableHead>
                <TableHead>显示名称</TableHead>
                <TableHead>平台身份</TableHead>
                <TableHead>状态</TableHead>
                <TableHead className="text-right">操作</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.loading ? (
                <TableRow>
                  <TableCell colSpan={6} className="h-32 text-center text-muted-foreground">
                    <output>正在加载账号…</output>
                  </TableCell>
                </TableRow>
              ) : !list.error && list.data?.records.length ? (
                list.data.records.map((user) => (
                  <TableRow key={user.id}>
                    <TableCell className="text-muted-foreground">{user.id}</TableCell>
                    <TableCell>{user.username}</TableCell>
                    <TableCell className="max-w-64 truncate" title={user.displayName}>
                      {user.displayName}
                    </TableCell>
                    <TableCell>
                      <Badge variant="secondary">
                        {user.platformAdmin ? '平台管理员' : '普通账号'}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <Badge variant={user.enabled ? 'secondary' : 'outline'}>
                        {user.enabled ? '启用' : '停用'}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        <Button variant="ghost" size="sm" onClick={() => setEditor(user)}>
                          编辑
                        </Button>
                        <DeleteRecord
                          name={user.displayName}
                          description="删除后，该账号将无法登录，所有租户成员关系也会移除。此操作无法撤销。"
                          disabled={user.platformAdmin}
                          onDelete={() => adminApi.deleteUser(user.id)}
                          onDeleted={list.reload}
                        />
                      </div>
                    </TableCell>
                  </TableRow>
                ))
              ) : (
                <TableRow>
                  <TableCell colSpan={6} className="h-32 text-center text-muted-foreground">
                    {list.error ? '加载失败，请重试' : '暂无账号'}
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
        <UserEditor
          user={editor}
          onClose={() => setEditor(undefined)}
          onSaved={(user) => {
            onUserUpdated(user)
            list.reload()
            setEditor(undefined)
          }}
        />
      )}
    </div>
  )
}

function UserEditor({
  user,
  onClose,
  onSaved,
}: {
  user: User | null
  onClose: () => void
  onSaved: (user: User) => void
}) {
  const submit = useSubmitting()
  const [enabled, setEnabled] = useState(user?.enabled === false ? 'false' : 'true')
  async function save(data: FormData) {
    await submit.run(async () => {
      const username = String(data.get('username') ?? '').trim()
      const displayName = String(data.get('displayName') ?? '').trim()
      if (!displayName) throw new Error('请输入显示名称')
      const saved = user
        ? await adminApi.updateUser(user.id, {
            username,
            displayName,
            enabled: enabled === 'true',
          })
        : await adminApi.createUser(username, String(data.get('password') ?? ''), displayName)
      onSaved(saved)
      return user ? '账号已更新' : '账号已创建'
    }, '保存失败')
  }
  return (
    <EditorSheet
      title={user ? '编辑账号' : '新增账号'}
      description={user ? '修改账号资料和启用状态。' : '创建普通账号，初始密码至少 12 个字符。'}
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
            用户名
            <Input
              name="username"
              required
              minLength={3}
              maxLength={64}
              pattern="[A-Za-z0-9][A-Za-z0-9_.-]{2,63}"
              defaultValue={user?.username ?? ''}
              autoComplete="off"
              placeholder="3–64 位字母、数字或 ._-"
            />
          </Label>
          <Label className="grid gap-2">
            显示名称
            <Input
              name="displayName"
              required
              maxLength={100}
              defaultValue={user?.displayName ?? ''}
            />
          </Label>
          {!user && (
            <Label className="grid gap-2">
              初始密码
              <Input
                name="password"
                type="password"
                required
                minLength={12}
                maxLength={128}
                autoComplete="new-password"
              />
            </Label>
          )}
          {user && (
            <div className="grid gap-2">
              <Label htmlFor="user-status">状态</Label>
              <Select
                value={enabled}
                onValueChange={setEnabled}
                disabled={submit.busy || user.platformAdmin}
              >
                <SelectTrigger id="user-status" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">启用</SelectItem>
                  <SelectItem value="false">停用</SelectItem>
                </SelectContent>
              </Select>
              {user.platformAdmin && (
                <p className="text-xs text-muted-foreground">平台管理员不能停用或删除。</p>
              )}
            </div>
          )}
        </fieldset>
        <div className="flex justify-end gap-2 border-t border-border p-6">
          <Button variant="outline" disabled={submit.busy} onClick={onClose}>
            取消
          </Button>
          <Button type="submit" loading={submit.busy} disabled={submit.busy}>
            {submit.busy ? '保存中…' : '保存'}
          </Button>
        </div>
      </Form>
    </EditorSheet>
  )
}
