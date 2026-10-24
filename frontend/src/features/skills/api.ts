import {
  type Page,
  isRecord as record,
  isNatural as natural,
  isPageOf as pageOf,
} from '@/lib/contracts'
import { isNull, request, type Validator } from '@/lib/api'
import { queryString } from '@/lib/utils'

export type Skill = {
  id: number
  slug: string
  name: string
  description: string
  tags: string[]
  origin: 'SYSTEM' | 'CUSTOM'
  enabled: boolean
  latestVersion: number
  revision: number
  updatedAt: string
  fileCount: number
  contentUpdatedAt: string
}
export type Version = {
  number: number
  fileCount: number
  totalBytes: number
  createdBy: number
  createdAt: string
}
export type SkillFile = { path: string; size: number; text: string | null; base64: string | null }
export type Content = { number: number; files: SkillFile[] }
export type PackagePreview = Content & { slug: string; name: string; description: string }
export type SkillOrigin = 'all' | 'CUSTOM' | 'SYSTEM'
export type SkillPage = Page<Skill> & { counts: { custom: number; system: number } }
export type Metadata = Pick<Skill, 'name' | 'description' | 'tags' | 'enabled' | 'revision'>
const strings = (v: unknown): v is string[] =>
  Array.isArray(v) && v.every((x: unknown) => typeof x === 'string')
export const isSkill: Validator<Skill> = (v): v is Skill =>
  record(v) &&
  natural(v.id) &&
  v.id > 0 &&
  typeof v.slug === 'string' &&
  typeof v.name === 'string' &&
  typeof v.description === 'string' &&
  strings(v.tags) &&
  (v.origin === 'SYSTEM' || v.origin === 'CUSTOM') &&
  typeof v.enabled === 'boolean' &&
  natural(v.latestVersion) &&
  natural(v.revision) &&
  typeof v.updatedAt === 'string' &&
  natural(v.fileCount) &&
  typeof v.contentUpdatedAt === 'string'
const isVersion: Validator<Version> = (v): v is Version =>
  record(v) &&
  natural(v.number) &&
  v.number > 0 &&
  natural(v.fileCount) &&
  natural(v.totalBytes) &&
  natural(v.createdBy) &&
  typeof v.createdAt === 'string'
const isFile: Validator<SkillFile> = (v): v is SkillFile =>
  record(v) &&
  typeof v.path === 'string' &&
  natural(v.size) &&
  ((typeof v.text === 'string' && v.base64 === null) ||
    (v.text === null && typeof v.base64 === 'string'))
export const isContent: Validator<Content> = (v): v is Content =>
  record(v) && natural(v.number) && Array.isArray(v.files) && v.files.every(isFile)
const isPackagePreview: Validator<PackagePreview> = (v): v is PackagePreview =>
  record(v) &&
  typeof v.slug === 'string' &&
  typeof v.name === 'string' &&
  typeof v.description === 'string' &&
  isContent(v)

export const isSkillPage: Validator<SkillPage> = (v): v is SkillPage =>
  record(v) &&
  record(v.counts) &&
  natural(v.counts.custom) &&
  natural(v.counts.system) &&
  pageOf(isSkill)(v)
const path = (tenantId: number, id?: number) =>
  `/tenants/${tenantId}/skills${id === undefined ? '' : `/${id}`}`
function form(input: object, files: SkillFile[]) {
  const data = new FormData()
  data.set('input', JSON.stringify({ ...input, paths: files.map((f) => f.path) }))
  for (const file of files) {
    const bytes =
      file.text !== null
        ? new TextEncoder().encode(file.text)
        : Uint8Array.from(atob(file.base64 ?? ''), (ch) => ch.charCodeAt(0))
    data.append('files', new Blob([bytes]), file.path.split('/').at(-1))
  }
  return { method: 'POST', body: data }
}
export const skillApi = {
  list: (tenantId: number, page: number, q: string, origin: SkillOrigin = 'all') =>
    request(
      `${path(tenantId)}${queryString({
        page,
        size: 12,
        q,
        origin: origin === 'all' ? undefined : origin,
      })}`,
      isSkillPage,
    ),
  detail: (tenantId: number, id: number) => request(path(tenantId, id), isSkill),
  create: (
    tenantId: number,
    input: { slug: string; name: string; description: string; tags: string[]; requestId: string },
    files: SkillFile[],
  ) => request(path(tenantId), isSkill, form(input, files)),
  update: (tenantId: number, id: number, input: Metadata) =>
    request(path(tenantId, id), isSkill, { method: 'PATCH', body: JSON.stringify(input) }),
  delete: (tenantId: number, skill: Skill) =>
    request(`${path(tenantId, skill.id)}?revision=${skill.revision}`, isNull, { method: 'DELETE' }),
  versions: (tenantId: number, id: number, page: number) =>
    request(`${path(tenantId, id)}/versions${queryString({ page, size: 20 })}`, pageOf(isVersion)),
  content: (tenantId: number, id: number, number: number) =>
    request(`${path(tenantId, id)}/versions/${number}`, isContent),
  save: (
    tenantId: number,
    id: number,
    baseVersion: number,
    requestId: string,
    files: SkillFile[],
  ) =>
    request(`${path(tenantId, id)}/versions`, isVersion, form({ baseVersion, requestId }, files)),
  preview: (tenantId: number, archive: File) => {
    const data = new FormData()
    data.set('archive', archive)
    return request(`${path(tenantId)}/package-preview`, isPackagePreview, {
      method: 'POST',
      body: data,
    })
  },
}

export type { Page } from '@/lib/contracts'
