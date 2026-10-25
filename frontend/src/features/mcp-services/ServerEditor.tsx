import { useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { Form } from '@/components/ui/form'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { EditorSheet } from '@/components/EditorSheet'
import { useSubmitting } from '@/lib/useSubmitting'
import { mcpApi, type Server, type TestResult, type Transport } from './api'
import { ProbeResult } from './ProbeResult'

type HeaderRow = { name: string; value: string }

/** 新建与编辑 MCP 服务：传输类型切换表单，鉴权头值编辑时留空沿用原密文。 */
export function ServerEditor({
  tenantId,
  server,
  stdioEnabled,
  onClose,
  onSaved,
}: {
  tenantId: number
  server: Server | null
  stdioEnabled: boolean
  onClose: () => void
  onSaved: (server: Server) => void
}) {
  const submit = useSubmitting()
  const [transport, setTransport] = useState<Transport>(server?.transport ?? 'HTTP_STREAMABLE')
  const [headers, setHeaders] = useState<HeaderRow[]>(
    server?.authHeaders.map((header) => ({ name: header.name, value: '' })) ?? [],
  )
  const [enabled, setEnabled] = useState(server?.enabled ?? true)
  const [probe, setProbe] = useState<TestResult | null>(null)
  const isStdio = transport === 'STDIO'
  const stdioBlocked = isStdio && !stdioEnabled

  function currentInput(event: HTMLFormElement): {
    name: string
    slug: string
    transport: Transport
    url?: string
    command?: string
    args?: string[]
    envVars?: string[]
    toolTimeoutMs: number | null
    authHeaders?: { name: string; value?: string }[]
    enabled: boolean
  } {
    const data = new FormData(event)
    const rawTimeout = String(data.get('toolTimeoutMs') ?? '').trim()
    return {
      name: String(data.get('name') ?? '').trim(),
      slug: String(data.get('slug') ?? '').trim(),
      transport,
      url: isStdio ? undefined : String(data.get('url') ?? '').trim(),
      command: isStdio ? String(data.get('command') ?? '').trim() : undefined,
      args: isStdio ? lines(String(data.get('args') ?? '')) : undefined,
      envVars: isStdio ? lines(String(data.get('envVars') ?? '')) : undefined,
      toolTimeoutMs: rawTimeout === '' ? null : Number(rawTimeout),
      authHeaders: isStdio
        ? undefined
        : headers
            .filter((row) => row.name.trim() !== '')
            .map((row) => ({ name: row.name.trim(), value: row.value.trim() })),
      enabled,
    }
  }

  async function testConnection(form: HTMLFormElement) {
    const input = currentInput(form)
    if (!input.name || !input.slug) {
      toast.error('请先填写名称与标识 slug')
      return
    }
    if (!isStdio && !input.url) {
      toast.error('请先填写服务地址')
      return
    }
    if (isStdio && !input.command) {
      toast.error('请先填写 stdio 命令')
      return
    }
    if (!server && input.authHeaders?.some((header) => !header.value)) {
      toast.error('新建时鉴权头必须填写值')
      return
    }
    await submit.run(async () => {
      const result = await mcpApi.test(tenantId, input)
      if (!submit.active.current) return
      setProbe(result)
      return undefined
    }, '连接测试失败')
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const input = currentInput(event.currentTarget)
    if (!server && input.authHeaders?.some((header) => !header.value)) {
      toast.error('新建时鉴权头必须填写值')
      return
    }
    await submit.run(async () => {
      const saved = server
        ? await mcpApi.update(tenantId, server.id, input)
        : await mcpApi.create(tenantId, input)
      if (!submit.active.current) return
      onSaved(saved)
      return server ? '服务已更新' : '服务已创建'
    }, '保存失败')
  }

  return (
    <EditorSheet
      title={server ? '编辑 MCP 服务' : '新建 MCP 服务'}
      description="远程服务按协议显式选择；stdio 由部署开关控制，默认关闭。"
      busy={submit.busy}
      onClose={onClose}
    >
      <Form onSubmit={(e) => void save(e)} className="grid gap-4 overflow-y-auto px-6 pb-6">
        <fieldset disabled={submit.busy} className="grid gap-4">
          <Label className="grid gap-2">
            名称
            <Input
              name="name"
              defaultValue={server?.name}
              maxLength={80}
              required
              placeholder="例如：Context7 文档检索"
            />
          </Label>
          <Label className="grid gap-2">
            标识 slug（工具名前缀使用）
            <Input
              name="slug"
              defaultValue={server?.slug}
              maxLength={32}
              required
              className="font-mono"
              placeholder="例如：context7"
            />
          </Label>
          <div className="grid gap-2">
            <Label htmlFor="mcp-transport">传输类型</Label>
            <Select
              value={transport}
              onValueChange={(value) => {
                if (value === 'HTTP_STREAMABLE' || value === 'HTTP_SSE' || value === 'STDIO') {
                  setTransport(value)
                  setProbe(null)
                }
              }}
            >
              <SelectTrigger id="mcp-transport" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="HTTP_STREAMABLE">Streamable HTTP（新协议）</SelectItem>
                <SelectItem value="HTTP_SSE">HTTP+SSE（老协议）</SelectItem>
                <SelectItem value="STDIO" disabled={!stdioEnabled}>
                  stdio 本地进程
                </SelectItem>
              </SelectContent>
            </Select>
            {stdioBlocked && (
              <p className="text-xs text-muted-foreground">
                stdio 总开关未启用（loopcraft.mcp.stdio.enabled=false），不能保存 stdio 服务。
              </p>
            )}
          </div>
          {!isStdio && (
            <>
              <Label className="grid gap-2">
                服务地址
                <Input
                  name="url"
                  type="url"
                  defaultValue={server?.url ?? ''}
                  maxLength={2048}
                  required
                  className="font-mono"
                  placeholder="https://mcp.example.com/mcp"
                />
              </Label>
              <p className="text-xs text-muted-foreground">
                Streamable HTTP 通常以 /mcp 结尾；HTTP+SSE 老协议通常以 /sse 结尾。
              </p>
              <div className="grid gap-2">
                <span className="text-sm font-medium">鉴权头（值保存后不再回显）</span>
                {headers.map((row, index) => (
                  <div
                    key={index}
                    className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_auto] gap-2"
                  >
                    <Input
                      aria-label={`鉴权头名称 ${index + 1}`}
                      className="font-mono"
                      maxLength={128}
                      placeholder="Header 名"
                      value={row.name}
                      onChange={(event) =>
                        setHeaders((rows) =>
                          rows.map((item, i) =>
                            i === index ? { ...item, name: event.target.value } : item,
                          ),
                        )
                      }
                    />
                    <Input
                      aria-label={`鉴权头值 ${index + 1}`}
                      type="password"
                      className="font-mono"
                      maxLength={8192}
                      autoComplete="new-password"
                      placeholder={server ? '留空保持不变' : '值'}
                      value={row.value}
                      onChange={(event) =>
                        setHeaders((rows) =>
                          rows.map((item, i) =>
                            i === index ? { ...item, value: event.target.value } : item,
                          ),
                        )
                      }
                    />
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`移除鉴权头 ${index + 1}`}
                      onClick={() => setHeaders((rows) => rows.filter((_, i) => i !== index))}
                    >
                      ×
                    </Button>
                  </div>
                ))}
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  className="justify-self-start"
                  disabled={headers.length >= 16}
                  onClick={() => setHeaders((rows) => [...rows, { name: '', value: '' }])}
                >
                  添加鉴权头
                </Button>
              </div>
            </>
          )}
          {isStdio && (
            <>
              <Label className="grid gap-2">
                命令
                <Input
                  name="command"
                  defaultValue={server?.command ?? ''}
                  maxLength={512}
                  required
                  className="font-mono"
                  placeholder="npx"
                />
              </Label>
              <Label htmlFor="mcp-args" className="grid gap-2">
                参数（每行一个）
              </Label>
              <Textarea
                id="mcp-args"
                name="args"
                rows={3}
                className="font-mono"
                defaultValue={server?.args.join('\n') ?? ''}
                placeholder={'@modelcontextprotocol/server-filesystem\n/data'}
              />
              <Label htmlFor="mcp-env" className="grid gap-2">
                环境变量（每行 KEY=VALUE）
              </Label>
              <Textarea
                id="mcp-env"
                name="envVars"
                rows={3}
                className="font-mono"
                defaultValue={server?.envVars.join('\n') ?? ''}
                placeholder="FS_ROOT=/data"
              />
            </>
          )}
          <Label className="grid gap-2">
            工具超时（毫秒，留空用全局 15s）
            <Input
              name="toolTimeoutMs"
              type="number"
              min={100}
              max={600000}
              step={100}
              defaultValue={server?.toolTimeoutMs ?? ''}
              className="font-mono"
            />
          </Label>
          <Label htmlFor="mcp-enabled" className="flex items-center gap-2">
            <Checkbox
              id="mcp-enabled"
              checked={enabled}
              onCheckedChange={(checked) => setEnabled(checked === true)}
            />
            启用该服务
          </Label>
        </fieldset>
        {probe && <ProbeResult result={probe} />}
        <div className="flex flex-wrap justify-end gap-2">
          <Button
            type="button"
            variant="outline"
            loading={submit.busy}
            onClick={(event) => {
              const form = event.currentTarget.form
              if (form) void testConnection(form)
            }}
          >
            {submit.busy ? '测试中…' : '测试连接'}
          </Button>
          <Button variant="outline" disabled={submit.busy} onClick={onClose}>
            取消
          </Button>
          <Button type="submit" loading={submit.busy}>
            {submit.busy ? '保存中…' : server ? '保存修改' : '保存服务'}
          </Button>
        </div>
      </Form>
    </EditorSheet>
  )
}

function lines(value: string): string[] {
  return value
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
}
