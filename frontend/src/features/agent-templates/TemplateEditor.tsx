import { useEffect, useRef, useState, type ReactNode } from 'react'
import { ArrowLeft, Copy, CircleAlert } from 'lucide-react'
import { toast } from 'sonner'
import { Form } from '@/components/ui/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { DeleteRecord } from '@/components/DeleteRecord'
import { ErrorState } from '@/components/ErrorState'
import { PageHeader } from '@/components/PageHeader'
import { useNavigationGuard } from '@/components/useNavigationGuard'
import { ApiError } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { useSubmitting } from '@/lib/useSubmitting'
import { templateApi, type TemplateDetail } from './api'
import {
  availability,
  configOf,
  configTabs,
  draftOf,
  tabOf,
  validateDraft,
  type ConfigTab,
  type Draft,
  type FieldError,
} from './config'
import { ModelPicker } from './ModelPicker'
import { SkillPicker } from './SkillPicker'

type EditorProps = {
  tenantId: number
  id: number | null
  copy: boolean
  onBack: () => void
  onSaved: () => void
  onCopy: (id: number) => void
  onDirtyChange: (dirty: boolean) => void
  onPendingChange: (pending: boolean) => void
}

export function TemplateEditor(props: EditorProps) {
  const [loaded, setLoaded] = useState<{ detail: TemplateDetail | null; copyName?: string } | null>(
    props.id === null ? { detail: null } : null,
  )
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    if (props.id === null) return
    let stale = false
    Promise.all([
      templateApi.detail(props.tenantId, props.id),
      props.copy ? templateApi.copyName(props.tenantId, props.id) : Promise.resolve(undefined),
    ])
      .then(([detail, copyName]) => {
        if (!stale) setLoaded({ detail, copyName })
      })
      .catch((cause: unknown) => {
        if (!stale) setError(errorMessage(cause, '加载模板失败'))
      })
    return () => {
      stale = true
    }
  }, [props.tenantId, props.id, props.copy, retry])
  if (error)
    return (
      <div className="space-y-4">
        <Button variant="ghost" onClick={props.onBack}>
          <ArrowLeft aria-hidden />
          模板配置
        </Button>
        <ErrorState
          variant="block"
          message={error}
          onRetry={() => {
            setError('')
            setRetry((r) => r + 1)
          }}
        />
      </div>
    )
  if (!loaded)
    return (
      <p aria-live="polite" className="py-12 text-sm text-muted-foreground">
        正在加载模板…
      </p>
    )
  return <TemplateForm {...props} initial={loaded.detail} suggestedName={loaded.copyName} />
}

function TemplateField({
  field,
  label,
  hint,
  error,
  children,
  optional = false,
}: {
  field: string
  label: string
  hint?: ReactNode
  error?: FieldError
  children: ReactNode
  optional?: boolean
}) {
  return (
    <div className="min-w-0 space-y-2">
      <Label htmlFor={field} className="flex justify-between gap-3">
        {label}
        {optional && <span className="text-xs font-normal text-muted-foreground">选填</span>}
      </Label>
      {children}
      <div id={`${field}-hint`} className="space-y-1 text-xs leading-5 text-muted-foreground">
        {hint}
        {error && <p className="text-danger">{error.message}</p>}
      </div>
    </div>
  )
}

function TemplateForm({
  initial,
  suggestedName,
  ...props
}: EditorProps & { initial: TemplateDetail | null; suggestedName?: string }) {
  const { onDirtyChange, onPendingChange } = props
  const submit = useSubmitting()
  const [saved, setSaved] = useState(props.copy ? null : initial)
  const startingDraft = () => ({
    ...draftOf(initial, props.copy),
    ...(suggestedName ? { name: suggestedName } : {}),
  })
  const [baseline, setBaseline] = useState(() => JSON.stringify(startingDraft()))
  const [draft, setDraft] = useState(startingDraft)
  const [tab, setTab] = useState<ConfigTab>('basic')
  const [checked, setChecked] = useState(false)
  const [serverErrors, setServerErrors] = useState<FieldError[]>([])
  const [conflict, setConflict] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const formRef = useRef<HTMLFormElement>(null)
  const retryRequest = useRef<{ payload: string; id: string } | null>(null)
  const busy = submit.busy || deleting
  const dirty = baseline !== JSON.stringify(draft)
  const errors = [...(checked ? validateDraft(draft) : []), ...serverErrors]
  const reasons = availability(draft)
  const navigation = useNavigationGuard({
    locked: dirty,
    busy,
    onBusy: () => toast.error('模板正在保存，请稍后再操作'),
    onDiscard: () => {},
    title: '放弃尚未保存的修改？',
    description: '继续后，当前尚未保存的配置会丢失。',
  })
  useEffect(() => {
    onDirtyChange(dirty)
    return () => onDirtyChange(false)
  }, [dirty, onDirtyChange])
  useEffect(() => {
    onPendingChange(busy)
    return () => onPendingChange(false)
  }, [busy, onPendingChange])
  useEffect(() => {
    function beforeUnload(event: BeforeUnloadEvent) {
      if (dirty || busy) {
        event.preventDefault()
        event.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', beforeUnload)
    return () => window.removeEventListener('beforeunload', beforeUnload)
  }, [dirty, busy])
  function change<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((d) => ({ ...d, [key]: value }))
    setServerErrors([])
  }
  function focusError(error: FieldError) {
    setTab(error.tab)
    requestAnimationFrame(() =>
      formRef.current?.querySelector<HTMLElement>(`[id="${error.field}"]`)?.focus(),
    )
  }
  function validate() {
    const problems = validateDraft(draft)
    setChecked(true)
    setServerErrors([])
    if (!problems.length) return true
    focusError(problems[0])
    return false
  }
  async function save() {
    if (busy || (saved && !dirty)) return
    const config = configOf(draft)
    const payload = JSON.stringify({
      config,
      id: saved?.id ?? null,
      baseRevision: saved?.revision ?? null,
    })
    if (retryRequest.current?.payload !== payload)
      retryRequest.current = { payload, id: crypto.randomUUID() }
    const requestId = retryRequest.current.id
    await submit.run(async () => {
      try {
        const result = await templateApi.save(
          props.tenantId,
          saved?.id ?? null,
          config,
          requestId,
          saved?.revision ?? null,
        )
        if (!submit.active.current) return
        const next = draftOf(result)
        setSaved(result)
        setDraft(next)
        setBaseline(JSON.stringify(next))
        setChecked(false)
        setConflict(false)
        setServerErrors([])
        retryRequest.current = null
        props.onSaved()
        return '模板已保存'
      } catch (cause) {
        if (submit.active.current && cause instanceof ApiError) {
          if (cause.status === 409) setConflict(true)
          if (cause.field) {
            const field = cause.field.startsWith('skillBindings') ? 'skillBindings' : cause.field
            const problem = { field, tab: tabOf(field), message: cause.message }
            setServerErrors([problem])
            focusError(problem)
          }
        }
        throw cause
      }
    }, '保存失败，输入已保留')
  }
  async function reload() {
    if (!saved) return
    await submit.run(async () => {
      const result = await templateApi.detail(props.tenantId, saved.id)
      if (!submit.active.current) return
      const next = draftOf(result)
      setSaved(result)
      setDraft(next)
      setBaseline(JSON.stringify(next))
      setChecked(false)
      setConflict(false)
      setServerErrors([])
      retryRequest.current = null
      return '已重新加载模板'
    }, '重新加载失败，草稿已保留')
  }
  const error = (field: string) => errors.find((e) => e.field === field)
  const input = (
    key:
      | 'name'
      | 'description'
      | 'temperature'
      | 'contextBudgetTokens'
      | 'keepRecentMessages'
      | 'maxIterations'
      | 'timeoutMinutes',
    type = 'text',
    placeholder?: string,
  ) => (
    <Input
      id={key}
      aria-label={key === 'name' ? '模板名称' : undefined}
      aria-invalid={!!error(key)}
      aria-describedby={`${key}-hint`}
      disabled={busy}
      type={type}
      step={type === 'number' ? 'any' : undefined}
      value={draft[key]}
      maxLength={key === 'name' ? 80 : key === 'description' ? 300 : undefined}
      placeholder={placeholder}
      onChange={(event) =>
        change(key, event.target.validity.badInput ? 'invalid' : event.target.value)
      }
    />
  )
  return (
    <div className="space-y-4">
      {navigation.dialog}
      <Button
        variant="ghost"
        size="sm"
        disabled={busy}
        onClick={() => navigation.guard(props.onBack)}
      >
        <ArrowLeft aria-hidden />
        模板配置
      </Button>
      <Form
        ref={formRef}
        validate={validate}
        onSubmit={(event) => {
          event.preventDefault()
          void save()
        }}
        onKeyDown={(event) => {
          if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
            event.preventDefault()
            formRef.current?.requestSubmit()
          }
        }}
      >
        <Tabs
          value={tab}
          onValueChange={(value) => {
            if (configTabs.some(([key]) => key === value)) setTab(tabOfValue(value))
          }}
        >
          <div className="sticky top-0 z-20 space-y-3 bg-canvas pt-3">
            <PageHeader
              title={saved?.name ?? '新建模板'}
              badge={dirty ? <Badge variant="secondary">未保存</Badge> : undefined}
              description="当前租户共享 · 所有成员可维护 · 四个栏目统一保存"
              actions={
                <>
                  <Button
                    type="button"
                    variant="outline"
                    disabled={busy}
                    onClick={() => navigation.guard(props.onBack)}
                  >
                    取消
                  </Button>
                  {saved && (
                    <Button
                      type="button"
                      variant="outline"
                      disabled={busy}
                      title="复制最近保存的配置"
                      onClick={() => navigation.guard(() => props.onCopy(saved.id))}
                    >
                      <Copy aria-hidden />
                      复制模板
                    </Button>
                  )}
                  <Button
                    type="submit"
                    loading={submit.busy}
                    disabled={busy || (!!saved && !dirty)}
                  >
                    {submit.busy ? '保存中…' : '保存模板'}
                  </Button>
                </>
              }
            />
            <TabsList aria-label="模板配置栏目" className="overflow-x-auto">
              {configTabs.map(([key, label]) => (
                <TabsTrigger key={key} value={key}>
                  {label}
                  {key === 'tools' && (
                    <span className="text-xs font-normal">{draft.skillBindings.length}</span>
                  )}
                  {errors.some((e) => e.tab === key) && (
                    <span className="text-xs text-danger">
                      {errors.filter((e) => e.tab === key).length} 项待调整
                    </span>
                  )}
                </TabsTrigger>
              ))}
            </TabsList>
          </div>
          {reasons.length > 0 && (
            <div className="mt-4 rounded-lg bg-warning-soft px-4 py-3 text-xs leading-6 text-warning">
              {reasons.join('；')}。可以继续维护和保存，当前配置不可用于运行。
            </div>
          )}
          {draft.contextBudgetTokens !== '' &&
            draft.model?.contextLimit &&
            Number(draft.contextBudgetTokens) > draft.model.contextLimit && (
              <div className="mt-4 flex flex-wrap items-center gap-2 rounded-lg bg-warning-soft px-4 py-3 text-xs text-warning">
                自定义预算超过当前模型上限。
                <Button type="button" variant="link" size="sm" onClick={() => setTab('context')}>
                  调整上下文预算 →
                </Button>
              </div>
            )}
          {conflict && (
            <Alert className="mt-4">
              <CircleAlert aria-hidden className="size-4" />
              <AlertTitle>其他成员已更新模板</AlertTitle>
              <AlertDescription>
                当前草稿已保留。重新加载将放弃草稿，读取最新配置。
                <Button
                  type="button"
                  variant="link"
                  disabled={busy}
                  onClick={() => navigation.guard(() => void reload())}
                >
                  重新加载
                </Button>
              </AlertDescription>
            </Alert>
          )}
          {errors.length > 0 && (
            <p aria-live="polite" className="mt-4 text-xs text-danger">
              有 {errors.length} 项配置需要调整，已在对应栏目和字段下方标出。
            </p>
          )}
          <TabsContent value="basic" forceMount hidden={tab !== 'basic'} className="pt-6">
            <Card className="max-w-[960px] space-y-2">
              <div>
                <h2 className="text-base font-semibold">基本信息</h2>
                <p className="mt-1 text-xs text-muted-foreground">
                  定义这份 Agent 的名称、模型和工作方式。
                </p>
              </div>
              <div className="grid gap-5 md:grid-cols-2">
                <TemplateField field="name" label="模板名称" error={error('name')}>
                  {input('name', 'text', '例如：研究与写作')}
                </TemplateField>
                <TemplateField
                  field="description"
                  label="描述"
                  optional
                  error={error('description')}
                >
                  {input('description', 'text', '这份模板适合处理什么任务')}
                </TemplateField>
              </div>
              <div className="grid gap-5 md:grid-cols-2">
                <TemplateField
                  field="modelId"
                  label="运行模型"
                  error={error('modelId')}
                  hint={draft.model ? `来自 ${draft.model.sourceName}` : '选择当前租户接入的模型'}
                >
                  <ModelPicker
                    tenantId={props.tenantId}
                    value={draft.model}
                    disabled={busy}
                    invalid={!!error('modelId')}
                    onChange={(model) => change('model', model)}
                  />
                </TemplateField>
                <TemplateField
                  field="temperature"
                  label="温度"
                  optional
                  error={error('temperature')}
                  hint={
                    draft.model?.temperatureSupported === false
                      ? '当前模型不支持温度，保持为空。'
                      : draft.model?.temperatureMin != null
                        ? `可填 ${draft.model.temperatureMin}–${draft.model.temperatureMax}；留空时不发送温度参数。`
                        : '留空时不发送温度参数；范围在模型接入维护。'
                  }
                >
                  {input('temperature', 'number', '使用模型默认值')}
                </TemplateField>
              </div>
              <TemplateField
                field="systemPrompt"
                label="系统提示词"
                optional
                error={error('systemPrompt')}
                hint={
                  <span className="flex justify-between gap-3">
                    <span>纯文本，不解析变量。</span>
                    <span>{draft.systemPrompt.length.toLocaleString()} / 20,000</span>
                  </span>
                }
              >
                <Textarea
                  id="systemPrompt"
                  aria-describedby="systemPrompt-hint"
                  aria-invalid={!!error('systemPrompt')}
                  value={draft.systemPrompt}
                  maxLength={20000}
                  disabled={busy}
                  className="min-h-52 leading-7"
                  placeholder="说明 Agent 的角色、工作方法与回答要求……"
                  onChange={(e) => change('systemPrompt', e.target.value)}
                />
              </TemplateField>
              <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border pt-4">
                <Label htmlFor="template-enabled">
                  <Checkbox
                    id="template-enabled"
                    checked={draft.enabled}
                    disabled={busy}
                    onCheckedChange={(v) => change('enabled', v === true)}
                  />
                  启用模板
                </Label>
                <span className="text-xs text-muted-foreground">
                  停用后仍可编辑，不可用于新任务
                </span>
              </div>
            </Card>
          </TabsContent>
          <TabsContent value="tools" className="pt-6">
            <div id="skillBindings" tabIndex={-1}>
              {error('skillBindings') && (
                <p className="mb-3 text-xs text-danger">{error('skillBindings')?.message}</p>
              )}
              <SkillPicker
                tenantId={props.tenantId}
                value={draft.skillBindings}
                disabled={busy}
                onChange={(value) => change('skillBindings', value)}
              />
            </div>
          </TabsContent>
          <TabsContent value="context" forceMount hidden={tab !== 'context'} className="pt-6">
            <Card className="max-w-[960px]">
              <div>
                <h2 className="text-base font-semibold">上下文预算</h2>
                <p className="mt-1 text-xs leading-5 text-muted-foreground">
                  包含完整输入和输出预留，留空时使用模型最大上下文。
                </p>
              </div>
              <div className="max-w-md">
                <TemplateField
                  field="contextBudgetTokens"
                  label="上下文预算（Token）"
                  optional
                  error={error('contextBudgetTokens')}
                  hint={
                    draft.model?.contextLimit
                      ? `模型上限：${draft.model.contextLimit.toLocaleString()} Token；当前${draft.contextBudgetTokens === '' ? '跟随模型' : '使用自定义预算'}。`
                      : draft.model
                        ? '当前模型尚未设置上下文上限，可保存，使用前需补齐。'
                        : '先在基本信息选择运行模型。'
                  }
                >
                  {input('contextBudgetTokens', 'number', '跟随模型上限')}
                </TemplateField>
              </div>
              <div className="mt-2 border-t border-border pt-5">
                <h2 className="text-base font-semibold">历史摘要</h2>
                <p className="mt-1 text-xs leading-5 text-muted-foreground">
                  保留最近的原始消息，将较早历史整理为摘要。
                </p>
              </div>
              <div className="grid gap-5 md:grid-cols-2">
                <TemplateField
                  field="summaryModelId"
                  label="摘要模型"
                  error={error('summaryModelId')}
                  hint={
                    draft.summaryModel
                      ? `来自 ${draft.summaryModel.sourceName}`
                      : `当前使用：${draft.model?.name ?? '跟随运行模型'}`
                  }
                >
                  <ModelPicker
                    tenantId={props.tenantId}
                    value={draft.summaryModel}
                    disabled={busy}
                    summary
                    invalid={!!error('summaryModelId')}
                    onChange={(value) => change('summaryModel', value)}
                  />
                </TemplateField>
                <TemplateField
                  field="keepRecentMessages"
                  label="保留最近消息数（条）"
                  error={error('keepRecentMessages')}
                  hint="默认 10 条，工具调用与结果整组保留。"
                >
                  {input('keepRecentMessages', 'number')}
                </TemplateField>
              </div>
              <p className="text-xs leading-6 text-muted-foreground">
                按消息条数计算，不是对话轮数；整组保留时可能略多于设定值。系统提示词独立保留，不占条数。输出预留和摘要触发由系统处理。
              </p>
            </Card>
          </TabsContent>
          <TabsContent value="runtime" forceMount hidden={tab !== 'runtime'} className="pt-6">
            <Card className="max-w-[960px]">
              <div>
                <h2 className="text-base font-semibold">运行设置</h2>
                <p className="mt-1 text-xs text-muted-foreground">
                  配置一次任务的循环次数和运行时间。
                </p>
              </div>
              <div className="grid gap-5 md:grid-cols-2">
                <TemplateField
                  field="maxIterations"
                  label="最大循环次数"
                  error={error('maxIterations')}
                  hint="0 表示不限次数，仅统计主模型调用。"
                >
                  {input('maxIterations', 'number')}
                </TemplateField>
                <TemplateField
                  field="timeoutMinutes"
                  label="运行超时（分钟）"
                  error={error('timeoutMinutes')}
                  hint="0 表示不限时；支持小数，如 0.5 为 30 秒。"
                >
                  {input('timeoutMinutes', 'number')}
                </TemplateField>
              </div>
            </Card>
          </TabsContent>
        </Tabs>
      </Form>
      <div className="flex flex-wrap items-center justify-between gap-3 text-xs text-muted-foreground">
        <span>
          {saved
            ? `最近更新：${new Date(saved.updatedAt).toLocaleString()}`
            : '首次保存后出现在模板列表'}
        </span>
        {saved && (
          <DeleteRecord
            name={saved.name}
            description="删除模板及其绑定，模型和 Skill 保留。"
            disabled={busy}
            onDelete={() => templateApi.delete(props.tenantId, saved.id, saved.revision)}
            onDeleted={props.onBack}
            onPendingChange={setDeleting}
          />
        )}
      </div>
    </div>
  )
}
function tabOfValue(value: string): ConfigTab {
  return value === 'tools' || value === 'context' || value === 'runtime' ? value : 'basic'
}
