import { type FormEvent } from 'react'
import { Form } from '@/components/ui/form'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { EditorSheet } from '@/components/EditorSheet'
import { useSubmitting } from '@/lib/useSubmitting'
import { llmApi, type Model } from './api'

export function ModelEditor({
  tenantId,
  sourceId,
  model,
  onClose,
  onSaved,
}: {
  tenantId: number
  sourceId: number
  model: Model | null
  onClose: () => void
  onSaved: () => void
}) {
  const submit = useSubmitting()
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const input = {
      name: String(data.get('name')).trim(),
      modelId: String(data.get('modelId')).trim(),
    }
    await submit.run(async () => {
      if (model) await llmApi.updateModel(tenantId, sourceId, model.id, input)
      else await llmApi.createModel(tenantId, sourceId, input)
      if (!submit.active.current) return
      onSaved()
      return model ? '模型已更新' : '模型已添加'
    }, '保存失败')
  }
  return (
    <EditorSheet
      title={model ? '编辑模型' : '手动添加模型'}
      description="模型 ID 必须与供应商实际提供的标识一致，区分大小写。"
      busy={submit.busy}
      onClose={onClose}
    >
      <Form className="grid gap-4 px-6 pb-6" onSubmit={(e) => void save(e)}>
        <fieldset disabled={submit.busy} className="grid gap-4">
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
          <Button variant="outline" disabled={submit.busy} onClick={onClose}>
            取消
          </Button>
          <Button type="submit" loading={submit.busy}>
            {submit.busy ? '保存中…' : '保存模型'}
          </Button>
        </div>
      </Form>
    </EditorSheet>
  )
}
