import { useState, type FormEvent } from 'react'
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
  const [temperatureSupport, setTemperatureSupport] = useState(
    model?.temperatureSupported === true
      ? 'yes'
      : model?.temperatureSupported === false
        ? 'no'
        : 'unknown',
  )
  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const input = {
      name: String(data.get('name')).trim(),
      modelId: String(data.get('modelId')).trim(),
      contextLimit: data.get('contextLimit') ? Number(data.get('contextLimit')) : null,
      temperatureSupported: temperatureSupport === 'unknown' ? null : temperatureSupport === 'yes',
      temperatureMin:
        temperatureSupport === 'yes' && data.get('temperatureMin')
          ? Number(data.get('temperatureMin'))
          : null,
      temperatureMax:
        temperatureSupport === 'yes' && data.get('temperatureMax')
          ? Number(data.get('temperatureMax'))
          : null,
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
            最大上下文（Token）
            <Input
              name="contextLimit"
              type="number"
              min="1"
              max="9007199254740991"
              step="1"
              defaultValue={model?.contextLimit ?? ''}
              placeholder="未知时留空"
            />
            <span className="text-xs font-normal text-muted-foreground">
              模板预算留空时采用该上限。请根据模型官方能力填写。
            </span>
          </Label>
          <Label className="grid gap-2" htmlFor="model-temperature-support">
            温度参数
            <Select
              value={temperatureSupport}
              disabled={submit.busy}
              onValueChange={setTemperatureSupport}
            >
              <SelectTrigger id="model-temperature-support" aria-label="温度参数支持情况">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="unknown">未知</SelectItem>
                <SelectItem value="yes">支持</SelectItem>
                <SelectItem value="no">不支持</SelectItem>
              </SelectContent>
            </Select>
          </Label>
          {temperatureSupport === 'yes' && (
            <div className="grid grid-cols-2 gap-4">
              <Label className="grid gap-2">
                温度下限
                <Input
                  name="temperatureMin"
                  type="number"
                  min="0"
                  max="100"
                  step="any"
                  defaultValue={model?.temperatureMin ?? ''}
                  placeholder="例如 0"
                />
              </Label>
              <Label className="grid gap-2">
                温度上限
                <Input
                  name="temperatureMax"
                  type="number"
                  min="0"
                  max="100"
                  step="any"
                  defaultValue={model?.temperatureMax ?? ''}
                  placeholder="例如 2"
                />
              </Label>
            </div>
          )}
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
