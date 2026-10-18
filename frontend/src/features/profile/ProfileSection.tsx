import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import type { User } from '@/features/auth/api'
import { Field } from '@/components/ui/form-field'

export function ProfileSection({
  user,
  busy,
  onRename,
  onChangePassword,
}: {
  user: User
  busy: boolean
  onRename: (name: string) => Promise<void>
  onChangePassword: (oldPassword: string, newPassword: string) => Promise<void>
}) {
  return (
    <div className="flex max-w-[680px] flex-col gap-4">
      <div className="flex items-start justify-between gap-4 pt-2 pb-4 [&_h1]:mb-2 [&_h1]:text-2xl [&_h1]:font-semibold [&_p:not(.eyebrow)]:text-secondary">
        <div>
          <p className="eyebrow mb-2 text-xs font-semibold tracking-wide text-muted-foreground">
            ACCOUNT
          </p>
          <h1>个人设置</h1>
          <p>更新你的显示名称或登录密码。</p>
        </div>
      </div>
      <Card className="[&_h2]:text-base [&_h2]:font-semibold">
        <h2>个人资料</h2>
        <p className="text-sm text-muted-foreground">
          账号名 <strong className="ml-1 font-medium text-secondary">@{user.username}</strong>
        </p>
        <form
          className="flex max-w-[440px] flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault()
            const data = new FormData(event.currentTarget)
            void onRename(String(data.get('name') ?? ''))
          }}
        >
          <Field label="显示名称" name="name" maxLength={100} placeholder="你的称呼" />
          <Button variant="default" className="self-start" type="submit" disabled={busy}>
            保存资料
          </Button>
        </form>
      </Card>
      <Card className="[&_h2]:text-base [&_h2]:font-semibold">
        <h2>修改密码</h2>
        <p className="text-sm text-muted-foreground">
          新密码长度需为 12–128 个字符，修改后需要重新登录。
        </p>
        <form
          className="flex max-w-[440px] flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault()
            const data = new FormData(event.currentTarget)
            void onChangePassword(
              String(data.get('oldPassword') ?? ''),
              String(data.get('newPassword') ?? ''),
            )
          }}
        >
          <Field
            label="当前密码"
            name="oldPassword"
            type="password"
            maxLength={128}
            autoComplete="current-password"
          />
          <Field
            label="新密码"
            name="newPassword"
            type="password"
            minLength={12}
            maxLength={128}
            autoComplete="new-password"
          />
          <Button variant="default" className="self-start" type="submit" disabled={busy}>
            更新密码
          </Button>
        </form>
      </Card>
    </div>
  )
}
