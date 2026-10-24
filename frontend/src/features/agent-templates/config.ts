import type { ModelChoice, SkillBinding, TemplateConfig, TemplateDetail } from './api'

export const configTabs = [
  ['basic', '基本信息'],
  ['tools', '工具'],
  ['context', '上下文'],
  ['runtime', '运行设置'],
] as const
export type ConfigTab = (typeof configTabs)[number][0]
export type Draft = {
  name: string
  description: string
  enabled: boolean
  model: ModelChoice | null
  systemPrompt: string
  temperature: string
  contextBudgetTokens: string
  summaryModel: ModelChoice | null
  keepRecentMessages: string
  maxIterations: string
  timeoutMinutes: string
  skillBindings: SkillBinding[]
}
export type FieldError = { field: string; tab: ConfigTab; message: string }
export function draftOf(detail: TemplateDetail | null, copy = false): Draft {
  return {
    name: detail ? (copy ? `${detail.name.slice(0, 76)}（副本）` : detail.name) : '',
    description: detail?.description ?? '',
    enabled: detail?.enabled ?? true,
    model: detail?.model ?? null,
    systemPrompt: detail?.systemPrompt ?? '',
    temperature: detail?.temperature?.toString() ?? '',
    contextBudgetTokens: detail?.contextBudgetTokens?.toString() ?? '',
    summaryModel: detail?.summaryModelId ? detail.summaryModel : null,
    keepRecentMessages: String(detail?.keepRecentMessages ?? 10),
    maxIterations: String(detail?.maxIterations ?? 0),
    timeoutMinutes: String(detail?.timeoutMinutes ?? 0),
    skillBindings: detail?.skillBindings ?? [],
  }
}
export function configOf(draft: Draft): TemplateConfig {
  return {
    name: draft.name.trim(),
    description: draft.description.trim(),
    enabled: draft.enabled,
    modelId: draft.model?.id ?? 0,
    systemPrompt: draft.systemPrompt,
    temperature: draft.temperature === '' ? null : Number(draft.temperature),
    contextBudgetTokens:
      draft.contextBudgetTokens === '' ? null : Number(draft.contextBudgetTokens),
    summaryModelId: draft.summaryModel?.id ?? null,
    keepRecentMessages: Number(draft.keepRecentMessages),
    maxIterations: Number(draft.maxIterations),
    timeoutMinutes: Number(draft.timeoutMinutes),
    skillBindings: draft.skillBindings.map(({ skillId, versionNumber }) => ({
      skillId,
      versionNumber,
    })),
  }
}
export function tabOf(field: string): ConfigTab {
  if (field.startsWith('skillBindings')) return 'tools'
  if (['contextBudgetTokens', 'summaryModelId', 'keepRecentMessages'].includes(field))
    return 'context'
  if (['maxIterations', 'timeoutMinutes'].includes(field)) return 'runtime'
  return 'basic'
}
export function validateDraft(draft: Draft): FieldError[] {
  const errors: FieldError[] = []
  const invalid = (field: string, message: string) =>
    errors.push({ field, tab: tabOf(field), message })
  if (!draft.name.trim() || draft.name.trim().length > 80)
    invalid('name', '请填写 1–80 字的模板名称')
  if (!draft.model) invalid('modelId', '请选择运行模型')
  const value = configOf(draft)
  if (draft.description.length > 300) invalid('description', '描述不能超过 300 字')
  if (draft.systemPrompt.length > 20000) invalid('systemPrompt', '系统提示词不能超过 20,000 字')
  if (value.temperature !== null) {
    if (!Number.isFinite(value.temperature) || value.temperature < 0 || value.temperature > 100)
      invalid('temperature', '温度应为非负有限数值')
    else if (draft.model?.temperatureSupported === false)
      invalid('temperature', '当前模型不支持温度，请清空此项')
    else if (
      draft.model?.temperatureMin != null &&
      draft.model.temperatureMax != null &&
      (value.temperature < draft.model.temperatureMin ||
        value.temperature > draft.model.temperatureMax)
    )
      invalid(
        'temperature',
        `可填范围为 ${draft.model.temperatureMin}–${draft.model.temperatureMax}`,
      )
  }
  if (value.contextBudgetTokens !== null) {
    if (!Number.isSafeInteger(value.contextBudgetTokens) || value.contextBudgetTokens < 1)
      invalid('contextBudgetTokens', '请输入正整数，或留空跟随模型上限')
    else if (draft.model?.contextLimit && value.contextBudgetTokens > draft.model.contextLimit)
      invalid(
        'contextBudgetTokens',
        `不能超过当前模型的 ${draft.model.contextLimit.toLocaleString()} Token 上限`,
      )
  }
  if (
    !draft.keepRecentMessages ||
    !Number.isInteger(value.keepRecentMessages) ||
    value.keepRecentMessages < 1 ||
    value.keepRecentMessages > 2147483647
  )
    invalid('keepRecentMessages', '请输入正整数消息条数')
  if (!draft.maxIterations || !Number.isSafeInteger(value.maxIterations) || value.maxIterations < 0)
    invalid('maxIterations', '请输入非负整数，0 表示不限次数')
  if (
    !draft.timeoutMinutes ||
    !Number.isFinite(value.timeoutMinutes) ||
    value.timeoutMinutes < 0 ||
    !/^\d+(\.\d{1,6})?$/.test(draft.timeoutMinutes) ||
    value.timeoutMinutes >= 1e12
  )
    invalid('timeoutMinutes', '请输入非负分钟数，最多 6 位小数，0 表示不限时')
  return errors
}
export function availability(draft: Draft): string[] {
  const reasons: string[] = []
  for (const [label, model] of [
    ['运行模型', draft.model],
    ['摘要模型', draft.summaryModel],
  ] as const) {
    if (!model) continue
    if (!model.enabled) reasons.push(`${label}或连接已停用`)
    if (model.contextLimit === null) reasons.push(`${label}尚未配置上下文上限`)
  }
  if (draft.skillBindings.some((s) => !s.enabled)) reasons.push('部分 Skill 已停用')
  if (
    draft.temperature !== '' &&
    draft.model &&
    (draft.model.temperatureSupported === null ||
      (draft.model.temperatureSupported &&
        (draft.model.temperatureMin === null || draft.model.temperatureMax === null)))
  )
    reasons.push('模型温度支持信息未补齐，可清空温度或在模型接入补齐')
  return reasons
}
