import { useEffect, useRef, useState, type FormEvent } from 'react'
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
import { EditorSheet } from '@/components/EditorSheet'
import { llmApi, type Source, type Protocol } from './api'

type ConnectionEditorProps = {
  tenantId: number
  source: Source | null
  onClose: () => void
  onSaved: (source: Source) => void
}

export function ConnectionEditor(props: ConnectionEditorProps) {
  return <ConnectionEditorState key={`${props.tenantId}:${props.source?.id ?? 'new'}`} {...props} />
}

function ConnectionEditorState({ tenantId, source, onClose, onSaved }: ConnectionEditorProps) {
  const active = useRef(true)
  const submitting = useRef(false)
  useEffect(() => {
    active.current = true
    return () => {
      active.current = false
    }
  }, [])
  const [busy, setBusy] = useState(false)
  const [protocol, setProtocol] = useState<Protocol>(source?.protocol ?? 'OPENAI_COMPATIBLE')
  const [clear, setClear] = useState(false)
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const data = new FormData(event.currentTarget)
    const input = {
      name: String(data.get('name')).trim(),
      supplierName: String(data.get('supplierName')).trim(),
      baseUrl: String(data.get('baseUrl')).trim(),
      apiKey: String(data.get('apiKey') ?? '').trim(),
      protocol,
      ...(source ? { clearApiKey: clear } : {}),
    }
    if (clear && input.apiKey) {
      if (active.current) toast.error('更换和清除 API Key 不能同时执行')
      return
    }
    submitting.current = true
    setBusy(true)
    try {
      const saved = source
        ? await llmApi.updateSource(tenantId, source.id, input)
        : await llmApi.createSource(tenantId, input)
      if (!active.current) return
      toast.success(source ? '连接已更新' : '连接已创建')
      onSaved(saved)
    } catch (cause) {
      if (active.current) toast.error(cause instanceof Error ? cause.message : '保存失败')
    } finally {
      submitting.current = false
      if (active.current) setBusy(false)
    }
  }
  return (
    <EditorSheet
      title={source ? '编辑供应商连接' : '新增供应商连接'}
      description="同一供应商可配置多个账号或环境，协议请按接口选择。"
      busy={busy}
      onClose={onClose}
    >
      <Form onSubmit={(e) => void save(e)} className="grid gap-4 overflow-y-auto px-6 pb-6">
        <fieldset disabled={busy} className="grid gap-4">
          <Label className="grid gap-2">
            供应商名称
            <Input
              name="supplierName"
              defaultValue={source?.supplierName}
              maxLength={80}
              required
              placeholder="例如：阿里云百炼"
            />
          </Label>
          <Label className="grid gap-2">
            连接名称
            <Input
              name="name"
              defaultValue={source?.name}
              maxLength={80}
              required
              placeholder="例如：研发环境"
            />
          </Label>
          <div className="grid gap-2">
            <Label htmlFor="llm-protocol">接口协议</Label>
            <Select
              value={protocol}
              onValueChange={(value) => {
                if (value === 'OPENAI_COMPATIBLE' || value === 'ANTHROPIC_COMPATIBLE')
                  setProtocol(value)
              }}
            >
              <SelectTrigger id="llm-protocol" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="OPENAI_COMPATIBLE">OpenAI 兼容</SelectItem>
                <SelectItem value="ANTHROPIC_COMPATIBLE">Anthropic 兼容</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <Label className="grid gap-2">
            API 根地址
            <Input
              name="baseUrl"
              type="url"
              defaultValue={source?.baseUrl}
              maxLength={2048}
              required
              placeholder="https://api.example.com/v1"
            />
          </Label>
          <p className="text-xs text-muted-foreground">
            包含版本前缀，不含 /models、/messages 或 /chat/completions。
          </p>
          <Label className="grid gap-2">
            API Key {source?.hasApiKey ? '· 已配置' : '· 未配置'}
            <Input
              name="apiKey"
              type="password"
              maxLength={8192}
              autoComplete="new-password"
              placeholder={source ? '留空保留原值' : '可稍后配置'}
            />
          </Label>
          {source?.hasApiKey && (
            <Label htmlFor="clear-llm-key" className="flex items-center gap-2">
              <Checkbox
                id="clear-llm-key"
                checked={clear}
                onCheckedChange={(checked) => setClear(checked === true)}
              />
              明确清除已保存的 API Key
            </Label>
          )}
        </fieldset>
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button type="submit" disabled={busy}>
            {busy ? '保存中…' : '保存连接'}
          </Button>
        </div>
      </Form>
    </EditorSheet>
  )
}
