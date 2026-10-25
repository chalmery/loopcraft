import { isRecord as record, isNatural as natural, isPageOf as isPage } from '@/lib/contracts'
import { isNull, request, type Validator } from '@/lib/api'
import { queryString } from '@/lib/utils'

export type Transport = 'HTTP_STREAMABLE' | 'HTTP_SSE' | 'STDIO'
export type Server = {
  id: number
  name: string
  slug: string
  transport: Transport
  url: string | null
  authHeaders: { name: string }[]
  command: string | null
  args: string[]
  envVars: string[]
  toolTimeoutMs: number | null
  enabled: boolean
  createdAt: string
  updatedAt: string
}
/** 鉴权头值仅在显式输入时提交；更新时同名留空由服务端沿用原密文。 */
export type HeaderInput = { name: string; value?: string }
export type ServerInput = {
  name: string
  slug: string
  transport: Transport
  url?: string
  authHeaders?: HeaderInput[]
  command?: string
  args?: string[]
  envVars?: string[]
  toolTimeoutMs?: number | null
  enabled?: boolean
}
export type ToolSummary = { name: string; description: string | null }
export type TestResult = {
  ok: boolean
  reason: string | null
  protocolVersion: string | null
  serverName: string | null
  serverVersion: string | null
  transport: string | null
  tools: ToolSummary[]
}
export type Options = { stdioEnabled: boolean }

export const TRANSPORT_LABELS: Record<Transport, string> = {
  HTTP_STREAMABLE: 'Streamable HTTP',
  HTTP_SSE: 'HTTP+SSE',
  STDIO: 'stdio',
}

const isTransport = (value: unknown): value is Transport =>
  value === 'HTTP_STREAMABLE' || value === 'HTTP_SSE' || value === 'STDIO'

export const isServer: Validator<Server> = (v): v is Server =>
  record(v) &&
  natural(v.id) &&
  v.id > 0 &&
  typeof v.name === 'string' &&
  typeof v.slug === 'string' &&
  isTransport(v.transport) &&
  (v.url === null || typeof v.url === 'string') &&
  Array.isArray(v.authHeaders) &&
  v.authHeaders.every((item: unknown) => record(item) && typeof item.name === 'string') &&
  (v.command === null || typeof v.command === 'string') &&
  Array.isArray(v.args) &&
  v.args.every((item: unknown) => typeof item === 'string') &&
  Array.isArray(v.envVars) &&
  v.envVars.every((item: unknown) => typeof item === 'string') &&
  (v.toolTimeoutMs === null || (natural(v.toolTimeoutMs) && v.toolTimeoutMs > 0)) &&
  typeof v.enabled === 'boolean' &&
  typeof v.createdAt === 'string' &&
  typeof v.updatedAt === 'string'

const isTool: Validator<ToolSummary> = (v): v is ToolSummary =>
  record(v) &&
  typeof v.name === 'string' &&
  (v.description === null || typeof v.description === 'string')

const isTestResult: Validator<TestResult> = (v): v is TestResult =>
  record(v) &&
  typeof v.ok === 'boolean' &&
  (v.reason === null || typeof v.reason === 'string') &&
  (v.protocolVersion === null || typeof v.protocolVersion === 'string') &&
  (v.serverName === null || typeof v.serverName === 'string') &&
  (v.serverVersion === null || typeof v.serverVersion === 'string') &&
  (v.transport === null || typeof v.transport === 'string') &&
  Array.isArray(v.tools) &&
  v.tools.every(isTool)

const isOptions: Validator<Options> = (v): v is Options =>
  record(v) && typeof v.stdioEnabled === 'boolean'

const path = (tenantId: number, serverId?: number) =>
  `/tenants/${tenantId}/mcp-servers${serverId === undefined ? '' : `/${serverId}`}`

const body = (input: unknown, method = 'POST') => ({ method, body: JSON.stringify(input) })

export const mcpApi = {
  servers: (tenantId: number, page: number, q: string) =>
    request(`${path(tenantId)}${queryString({ page, size: 20, q })}`, isPage(isServer)),
  options: (tenantId: number) => request(`${path(tenantId)}/options`, isOptions),
  create: (tenantId: number, input: ServerInput) => request(path(tenantId), isServer, body(input)),
  update: (tenantId: number, serverId: number, input: Partial<ServerInput>) =>
    request(path(tenantId, serverId), isServer, body(input, 'PATCH')),
  delete: (tenantId: number, serverId: number) =>
    request(path(tenantId, serverId), isNull, { method: 'DELETE' }),
  /** 用未保存的表单配置探测连接。 */
  test: (tenantId: number, input: ServerInput) =>
    request(`${path(tenantId)}/test`, isTestResult, body(input)),
  /** 用已保存配置探测连接。 */
  testSaved: (tenantId: number, serverId: number) =>
    request(`${path(tenantId, serverId)}/test`, isTestResult, body({})),
}

export type { Page } from '@/lib/contracts'
