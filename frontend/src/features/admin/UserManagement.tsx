import { useState } from 'react'
import { toast } from 'sonner'
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
import type { User } from '@/features/auth/api'
import { adminApi } from './api'
import { useAdminPage } from './useAdminPage'
import { DeleteRecord, EditorSheet, ListHeading, ListPagination } from './AdminListParts'

export function UserManagement({ onUserUpdated }: { onUserUpdated: (user: User) => void }) {
  const list = useAdminPage(adminApi.users)
  const [editor, setEditor] = useState<User | null | undefined>()
  return (
    <div>
      <ListHeading
        title="账号管理"
        description="管理平台账号、基本资料与启用状态。"
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
              <TableHead>用户名</TableHead>
              <TableHead>显示名称</TableHead>
              <TableHead>平台身份</TableHead>
              <TableHead>状态</TableHead>
              <TableHead className="text-right">操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {list.pending ? (
              <TableRow>
                <TableCell colSpan={6} className="h-32 text-center text-muted-foreground">
                  正在加载账号…
                </TableCell>
              </TableRow>
            ) : list.data?.records.length ? (
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
                  {list.error ? '加载失败，请刷新重试' : '暂无账号'}
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
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [enabled, setEnabled] = useState(user?.enabled === false ? 'false' : 'true')
  async function save(data: FormData) {
    setBusy(true)
    setError('')
    try {
      const username = String(data.get('username') ?? '').trim()
      const displayName = String(data.get('displayName') ?? '').trim()
      if (!displayName) throw new Error('请输入显示名称')
      const saved = user
        ? await adminApi.updateUser(user.id, { username, displayName, enabled: enabled === 'true' })
        : await adminApi.createUser(username, String(data.get('password') ?? ''), displayName)
      toast.success(user ? '账号已更新' : '账号已创建')
      onSaved(saved)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '保存失败')
    } finally {
      setBusy(false)
    }
  }
  return (
    <EditorSheet
      title={user ? '编辑账号' : '新增账号'}
      description={user ? '修改账号资料和启用状态。' : '创建普通账号，初始密码至少 12 个字符。'}
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
                disabled={busy || user.platformAdmin}
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
          <Button type="submit" disabled={busy}>
            {busy ? '保存中…' : '保存'}
          </Button>
        </div>
      </form>
    </EditorSheet>
  )
}
