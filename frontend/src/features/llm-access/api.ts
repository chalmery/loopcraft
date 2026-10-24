import { isRecord as record, isNatural as natural, isPageOf as isPage } from '@/lib/contracts'
import { isNull, request, type Validator } from '@/lib/api'
import { queryString } from '@/lib/utils'

export type Protocol = 'OPENAI_COMPATIBLE' | 'ANTHROPIC_COMPATIBLE'
export type Source = {
  id: number
  name: string
  supplierName: string
  protocol: Protocol
  baseUrl: string
  hasApiKey: boolean
  enabled: boolean
  modelCount: number
  updatedAt: string
}
export type Model = {
  id: number
  sourceId: number
  name: string
  modelId: string
  origin: 'MANUAL' | 'DISCOVERY'
  enabled: boolean
  updatedAt: string
}
export type Candidate = { name: string; modelId: string }
export type Discovery = { records: Candidate[]; nextCursor: string | null; supported: boolean }
export type SourceInput = {
  name: string
  supplierName: string
  protocol: Protocol
  baseUrl: string
  apiKey?: string
  clearApiKey?: boolean
  enabled?: boolean
}
export const isSource: Validator<Source> = (v): v is Source =>
  record(v) &&
  natural(v.id) &&
  v.id > 0 &&
  typeof v.name === 'string' &&
  typeof v.supplierName === 'string' &&
  (v.protocol === 'OPENAI_COMPATIBLE' || v.protocol === 'ANTHROPIC_COMPATIBLE') &&
  typeof v.baseUrl === 'string' &&
  typeof v.hasApiKey === 'boolean' &&
  typeof v.enabled === 'boolean' &&
  natural(v.modelCount) &&
  typeof v.updatedAt === 'string'
export const isModel: Validator<Model> = (v): v is Model =>
  record(v) &&
  natural(v.id) &&
  v.id > 0 &&
  natural(v.sourceId) &&
  v.sourceId > 0 &&
  typeof v.name === 'string' &&
  typeof v.modelId === 'string' &&
  (v.origin === 'MANUAL' || v.origin === 'DISCOVERY') &&
  typeof v.enabled === 'boolean' &&
  typeof v.updatedAt === 'string'
const isCandidate: Validator<Candidate> = (v): v is Candidate =>
  record(v) && typeof v.name === 'string' && typeof v.modelId === 'string'

const isDiscovery: Validator<Discovery> = (v): v is Discovery =>
  record(v) &&
  Array.isArray(v.records) &&
  v.records.every(isCandidate) &&
  (v.nextCursor === null || typeof v.nextCursor === 'string') &&
  typeof v.supported === 'boolean'
const isImported: Validator<{ created: Model[]; skippedModelIds: string[] }> = (
  v,
): v is { created: Model[]; skippedModelIds: string[] } =>
  record(v) &&
  Array.isArray(v.created) &&
  v.created.every(isModel) &&
  Array.isArray(v.skippedModelIds) &&
  v.skippedModelIds.every((id: unknown) => typeof id === 'string')
const path = (tenantId: number, sourceId?: number) =>
  `/tenants/${tenantId}/llm-sources${sourceId === undefined ? '' : `/${sourceId}`}`
const body = (input: unknown, method = 'POST') => ({ method, body: JSON.stringify(input) })
export const llmApi = {
  sources: (tenantId: number, page: number, q: string) =>
    request(`${path(tenantId)}${queryString({ page, size: 20, q })}`, isPage(isSource)),
  source: (tenantId: number, sourceId: number) => request(path(tenantId, sourceId), isSource),
  createSource: (tenantId: number, input: SourceInput) =>
    request(path(tenantId), isSource, body(input)),
  updateSource: (tenantId: number, sourceId: number, input: Partial<SourceInput>) =>
    request(path(tenantId, sourceId), isSource, body(input, 'PATCH')),
  deleteSource: (tenantId: number, sourceId: number) =>
    request(path(tenantId, sourceId), isNull, { method: 'DELETE' }),
  models: (tenantId: number, sourceId: number, page: number, q = '', size = 20) =>
    request(`${path(tenantId, sourceId)}/models${queryString({ page, size, q })}`, isPage(isModel)),
  createModel: (tenantId: number, sourceId: number, input: Candidate) =>
    request(`${path(tenantId, sourceId)}/models`, isModel, body(input)),
  updateModel: (
    tenantId: number,
    sourceId: number,
    id: number,
    input: Partial<Candidate> & { enabled?: boolean },
  ) => request(`${path(tenantId, sourceId)}/models/${id}`, isModel, body(input, 'PATCH')),
  deleteModel: (tenantId: number, sourceId: number, id: number) =>
    request(`${path(tenantId, sourceId)}/models/${id}`, isNull, { method: 'DELETE' }),
  discover: (tenantId: number, sourceId: number, cursor?: string) =>
    request(
      `${path(tenantId, sourceId)}/model-discovery`,
      isDiscovery,
      body({ cursor, limit: 100 }),
    ),
  importModels: (tenantId: number, sourceId: number, models: Candidate[]) =>
    request(`${path(tenantId, sourceId)}/models/import`, isImported, body({ models })),
  async existingIds(tenantId: number, sourceId: number): Promise<Set<string>> {
    const ids = new Set<string>()
    for (let page = 1; ; page += 1) {
      const result = await llmApi.models(tenantId, sourceId, page, '', 100)
      result.records.forEach((m) => ids.add(m.modelId))
      if (page * result.size >= result.total || result.records.length === 0) return ids
    }
  },
}

export type { Page } from '@/lib/contracts'
