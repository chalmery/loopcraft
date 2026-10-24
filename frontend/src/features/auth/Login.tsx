import { useState, type FormEvent } from 'react'
import { Eye, EyeOff } from 'lucide-react'
import { Form } from '@/components/ui/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Field } from '@/components/ui/form-field'
import { LoopCraftMark } from '@/components/layout/LoopCraftMark'
import { useSubmitting } from '@/lib/useSubmitting'

export function Login({
  onLogin,
}: {
  onLogin: (username: string, password: string) => Promise<void>
}) {
  const submit = useSubmitting()
  const [showPassword, setShowPassword] = useState(false)
  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    await submit.run(async () => {
      await onLogin(String(data.get('username') ?? ''), String(data.get('password') ?? ''))
    }, '登录失败，请稍后重试')
  }
  return (
    <main className="flex min-h-screen items-center justify-center p-4">
      <Card className="w-full max-w-[360px] gap-5 p-6" aria-labelledby="login-title">
        <div className="flex items-center justify-center gap-3 text-base font-semibold">
          <LoopCraftMark className="size-9" />
          <h1 id="login-title" className="text-xl font-semibold">
            LoopCraft
          </h1>
        </div>
        <Form className="flex flex-col gap-3" onSubmit={handleSubmit}>
          <Field
            label="用户名"
            name="username"
            autoComplete="username"
            maxLength={64}
            placeholder="输入用户名"
          />
          <Label className="grid gap-2 text-sm font-medium text-secondary">
            <span>密码</span>
            <span className="relative block">
              <Input
                name="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                maxLength={128}
                placeholder="输入密码"
                className="pr-10"
              />
              <Button
                variant="ghost"
                size="icon-sm"
                type="button"
                className="absolute top-1/2 right-1 size-7 -translate-y-1/2"
                aria-label={showPassword ? '隐藏密码' : '显示密码'}
                onClick={() => setShowPassword((value) => !value)}
              >
                {showPassword ? <EyeOff aria-hidden /> : <Eye aria-hidden />}
              </Button>
            </span>
          </Label>
          <Button
            variant="default"
            className="w-full"
            type="submit"
            loading={submit.busy}
            disabled={submit.busy}
          >
            {submit.busy ? '正在登录…' : '登录'}
          </Button>
        </Form>
      </Card>
    </main>
  )
}
