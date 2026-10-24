import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { Form } from '@/components/ui/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Field } from '@/components/ui/form-field'
import { LoopCraftMark } from '@/components/layout/LoopCraftMark'

export function Login({
  onLogin,
}: {
  onLogin: (username: string, password: string) => Promise<void>
}) {
  const [busy, setBusy] = useState(false)
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    setBusy(true)
    try {
      await onLogin(String(data.get('username') ?? ''), String(data.get('password') ?? ''))
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '登录失败，请稍后重试')
    } finally {
      setBusy(false)
    }
  }
  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card
        className="w-full max-w-[360px] gap-5 p-6 [&_h1]:text-xl [&_h1]:font-semibold"
        aria-labelledby="login-title"
      >
        <div className="flex items-center justify-center gap-3 text-base font-semibold">
          <LoopCraftMark className="size-9" />
          <h1 id="login-title">LoopCraft</h1>
        </div>
        <Form className="flex flex-col gap-3" onSubmit={submit}>
          <Field
            label="用户名"
            name="username"
            autoComplete="username"
            maxLength={64}
            placeholder="输入用户名"
          />
          <Field
            label="密码"
            name="password"
            type="password"
            autoComplete="current-password"
            maxLength={128}
            placeholder="输入密码"
          />
          <Button variant="default" className="w-full" type="submit" disabled={busy}>
            {busy ? '正在登录…' : '登录'}
          </Button>
        </Form>
      </Card>
    </main>
  )
}
