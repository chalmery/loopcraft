import { isNull, request, type Validator } from '@/lib/api'

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
export type Page<T> = { records: T[]; total: number; page: number; size: number }
export type SkillOrigin = 'all' | 'CUSTOM' | 'SYSTEM'
export type SkillPage = Page<Skill> & { counts: { custom: number; system: number } }
export type Metadata = Pick<Skill, 'name' | 'description' | 'tags' | 'enabled' | 'revision'>
const record = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null
const natural = (v: unknown): v is number =>
  typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
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
function pageOf<T>(validator: Validator<T>): Validator<Page<T>> {
  return (v): v is Page<T> =>
    record(v) &&
    Array.isArray(v.records) &&
    v.records.every(validator) &&
    natural(v.total) &&
    natural(v.page) &&
    v.page > 0 &&
    natural(v.size) &&
    v.size > 0
}
export const isSkillPage: Validator<SkillPage> = (v): v is SkillPage =>
  record(v) &&
  record(v.counts) &&
  natural(v.counts.custom) &&
  natural(v.counts.system) &&
  pageOf(isSkill)(v)
const path = (tenant: number, id?: number) =>
  `/tenants/${tenant}/skills${id === undefined ? '' : `/${id}`}`
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
  list: (tenant: number, page: number, q: string, origin: SkillOrigin = 'all') =>
    request(
      `${path(tenant)}?page=${page}&size=12&q=${encodeURIComponent(q)}${origin === 'all' ? '' : `&origin=${origin}`}`,
      isSkillPage,
    ),
  detail: (tenant: number, id: number) => request(path(tenant, id), isSkill),
  create: (
    tenant: number,
    input: { slug: string; name: string; description: string; tags: string[]; requestId: string },
    files: SkillFile[],
  ) => request(path(tenant), isSkill, form(input, files)),
  update: (tenant: number, id: number, input: Metadata) =>
    request(path(tenant, id), isSkill, { method: 'PATCH', body: JSON.stringify(input) }),
  delete: (tenant: number, skill: Skill) =>
    request(`${path(tenant, skill.id)}?revision=${skill.revision}`, isNull, { method: 'DELETE' }),
  versions: (tenant: number, id: number, page: number) =>
    request(`${path(tenant, id)}/versions?page=${page}&size=20`, pageOf(isVersion)),
  content: (tenant: number, id: number, number: number) =>
    request(`${path(tenant, id)}/versions/${number}`, isContent),
  save: (tenant: number, id: number, baseVersion: number, requestId: string, files: SkillFile[]) =>
    request(`${path(tenant, id)}/versions`, isVersion, form({ baseVersion, requestId }, files)),
  preview: (tenant: number, archive: File) => {
    const data = new FormData()
    data.set('archive', archive)
    return request(`${path(tenant)}/package-preview`, isPackagePreview, {
      method: 'POST',
      body: data,
    })
  },
}
