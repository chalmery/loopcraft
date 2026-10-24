import { useEffect, useRef, useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { Form } from '@/components/ui/form'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { EditorSheet } from '@/components/EditorSheet'
import { llmApi, type Model } from './api'

type ModelEditorProps = {
  tenantId: number
  sourceId: number
  model: Model | null
  onClose: () => void
  onSaved: () => void
}

export function ModelEditor(props: ModelEditorProps) {
  return (
    <ModelEditorState
      key={`${props.tenantId}:${props.sourceId}:${props.model?.id ?? 'new'}`}
      {...props}
    />
  )
}

function ModelEditorState({ tenantId, sourceId, model, onClose, onSaved }: ModelEditorProps) {
  const active = useRef(true)
  const submitting = useRef(false)
  useEffect(() => {
    active.current = true
    return () => {
      active.current = false
    }
  }, [])
  const [busy, setBusy] = useState(false)
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const data = new FormData(event.currentTarget)
    const input = {
      name: String(data.get('name')).trim(),
      modelId: String(data.get('modelId')).trim(),
    }
    submitting.current = true
    setBusy(true)
    try {
      if (model) await llmApi.updateModel(tenantId, sourceId, model.id, input)
      else await llmApi.createModel(tenantId, sourceId, input)
      if (!active.current) return
      toast.success(model ? '模型已更新' : '模型已添加')
      onSaved()
    } catch (cause) {
      if (active.current) toast.error(cause instanceof Error ? cause.message : '保存失败')
    } finally {
      submitting.current = false
      if (active.current) setBusy(false)
    }
  }
  return (
    <EditorSheet
      title={model ? '编辑模型' : '手动添加模型'}
      description="模型 ID 必须与供应商实际提供的标识一致，区分大小写。"
      busy={busy}
      onClose={onClose}
    >
      <Form className="grid gap-4 px-6 pb-6" onSubmit={(e) => void save(e)}>
        <fieldset disabled={busy} className="grid gap-4">
          <Label className="grid gap-2">
            显示名称
            <Input name="name" defaultValue={model?.name} maxLength={80} required />
          </Label>
          <Label className="grid gap-2">
            模型 ID
            <Input name="modelId" defaultValue={model?.modelId} maxLength={160} required />
          </Label>
        </fieldset>
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={busy} onClick={onClose}>
            取消
          </Button>
          <Button type="submit" disabled={busy}>
            {busy ? '保存中…' : '保存模型'}
          </Button>
        </div>
      </Form>
    </EditorSheet>
  )
}
