import { isArrayOf, isNull, request, type Validator } from '@/lib/api'
import { isRecord, isNatural, isPositiveInteger, isPageOf, type Page } from '@/lib/contracts'
import { queryString } from '@/lib/utils'
import { isModelCapabilities, type ModelCapabilities } from '@/features/llm-access/api'

export type ModelChoice = ModelCapabilities & {
  id: number
  sourceId: number
  name: string
  sourceName: string
  enabled: boolean
}
export type SkillBinding = {
  skillId: number
  versionNumber: number
  name: string
  description: string
  enabled: boolean
  latestVersion: number
}
export type TemplateSummary = {
  id: number
  name: string
  description: string
  enabled: boolean
  modelName: string
  skillCount: number
  updatedAt: string
  available: boolean
  unavailableReasons: string[]
}
export type TemplateConfig = {
  name: string
  description: string
  enabled: boolean
  modelId: number
  systemPrompt: string
  temperature: number | null
  contextBudgetTokens: number | null
  summaryModelId: number | null
  keepRecentMessages: number
  maxIterations: number
  timeoutMinutes: number
  skillBindings: { skillId: number; versionNumber: number }[]
}
export type TemplateDetail = Omit<TemplateConfig, 'skillBindings'> & {
  id: number
  revision: number
  updatedAt: string
  model: ModelChoice
  summaryModel: ModelChoice
  skillBindings: SkillBinding[]
  available: boolean
  unavailableReasons: string[]
}
export type TemplatePage = Page<TemplateSummary> & {
  counts: { all: number; enabled: number; disabled: number }
}
const strings = (v: unknown): v is string[] =>
  Array.isArray(v) && v.every((x) => typeof x === 'string')
const nullableNumber = (v: unknown): v is number | null =>
  v === null || (typeof v === 'number' && Number.isFinite(v))
export const isModelChoice: Validator<ModelChoice> = (v): v is ModelChoice =>
  isRecord(v) &&
  isPositiveInteger(v.id) &&
  isPositiveInteger(v.sourceId) &&
  typeof v.name === 'string' &&
  typeof v.sourceName === 'string' &&
  typeof v.enabled === 'boolean' &&
  isModelCapabilities(v)
export const isBinding: Validator<SkillBinding> = (v): v is SkillBinding =>
  isRecord(v) &&
  isPositiveInteger(v.skillId) &&
  isPositiveInteger(v.versionNumber) &&
  typeof v.name === 'string' &&
  typeof v.description === 'string' &&
  typeof v.enabled === 'boolean' &&
  isPositiveInteger(v.latestVersion)
export const isTemplate: Validator<TemplateDetail> = (v): v is TemplateDetail =>
  isRecord(v) &&
  isPositiveInteger(v.id) &&
  isNatural(v.revision) &&
  typeof v.updatedAt === 'string' &&
  typeof v.name === 'string' &&
  typeof v.description === 'string' &&
  typeof v.enabled === 'boolean' &&
  isPositiveInteger(v.modelId) &&
  typeof v.systemPrompt === 'string' &&
  nullableNumber(v.temperature) &&
  (v.contextBudgetTokens === null || isPositiveInteger(v.contextBudgetTokens)) &&
  (v.summaryModelId === null || isPositiveInteger(v.summaryModelId)) &&
  isPositiveInteger(v.keepRecentMessages) &&
  isNatural(v.maxIterations) &&
  typeof v.timeoutMinutes === 'number' &&
  Number.isFinite(v.timeoutMinutes) &&
  v.timeoutMinutes >= 0 &&
  isModelChoice(v.model) &&
  isModelChoice(v.summaryModel) &&
  isArrayOf(isBinding)(v.skillBindings) &&
  typeof v.available === 'boolean' &&
  strings(v.unavailableReasons)
const isSummary: Validator<TemplateSummary> = (v): v is TemplateSummary =>
  isRecord(v) &&
  isPositiveInteger(v.id) &&
  typeof v.name === 'string' &&
  typeof v.description === 'string' &&
  typeof v.enabled === 'boolean' &&
  typeof v.modelName === 'string' &&
  isNatural(v.skillCount) &&
  typeof v.updatedAt === 'string' &&
  typeof v.available === 'boolean' &&
  strings(v.unavailableReasons)
const isList: Validator<TemplatePage> = (v): v is TemplatePage =>
  isRecord(v) &&
  isRecord(v.counts) &&
  isNatural(v.counts.all) &&
  isNatural(v.counts.enabled) &&
  isNatural(v.counts.disabled) &&
  isPageOf(isSummary)(v)
const path = (tenantId: number, id?: number) =>
  `/tenants/${tenantId}/agent-templates${id === undefined ? '' : `/${id}`}`
export const templateApi = {
  list: (tenantId: number, page: number, q: string, filter: string) =>
    request(
      `${path(tenantId)}${queryString({ page, size: 12, q, enabled: filter === 'all' ? undefined : String(filter === 'enabled') })}`,
      isList,
    ),
  detail: (tenantId: number, id: number) => request(path(tenantId, id), isTemplate),
  copyName: (tenantId: number, id: number) =>
    request(`${path(tenantId, id)}/copy-name`, (v): v is string => typeof v === 'string'),
  models: (tenantId: number, page: number, q: string) =>
    request(
      `${path(tenantId)}/models${queryString({ page, size: 20, q })}`,
      isPageOf(isModelChoice),
    ),
  save: (
    tenantId: number,
    id: number | null,
    config: TemplateConfig,
    requestId: string,
    baseRevision: number | null,
  ) =>
    request(path(tenantId, id ?? undefined), isTemplate, {
      method: id === null ? 'POST' : 'PUT',
      body: JSON.stringify({ ...config, requestId, baseRevision }),
    }),
  delete: (tenantId: number, id: number, revision: number) =>
    request(`${path(tenantId, id)}?baseRevision=${revision}`, isNull, { method: 'DELETE' }),
}
