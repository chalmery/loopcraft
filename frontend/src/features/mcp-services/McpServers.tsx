import { useEffect, useState } from 'react'
import { toast } from 'sonner'
import { Plus, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { DeleteRecord } from '@/components/DeleteRecord'
import { ErrorState } from '@/components/ErrorState'
import { ListPagination } from '@/components/ListPagination'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { usePagedList } from '@/lib/usePagedList'
import { useSubmitting } from '@/lib/useSubmitting'
import { mcpApi, TRANSPORT_LABELS, type Options, type Server, type TestResult } from './api'
import { ServerEditor } from './ServerEditor'
import { ProbeResult } from './ProbeResult'

/** 租户 MCP 服务清单：成员只读，负责人与平台管理员可管理。 */
export function McpServers({ tenantId, canManage }: { tenantId: number; canManage: boolean }) {
  const submit = useSubmitting()
  const [query, setQuery] = useState('')
  const debouncedQuery = useDebouncedValue(query)
  const list = usePagedList(
    (page: number) => mcpApi.servers(tenantId, page, debouncedQuery),
    [tenantId, debouncedQuery],
  )
  const [options, setOptions] = useState<Options | null>(null)
  const [editor, setEditor] = useState<{ server: Server | null } | null>(null)
  const [probe, setProbe] = useState<{ title: string; result: TestResult } | null>(null)

  useEffect(() => {
    let stale = false
    void mcpApi.options(tenantId).then(
      (loaded) => {
        if (!stale) setOptions(loaded)
      },
      () => {
        if (!stale) setOptions({ stdioEnabled: false })
      },
    )
    return () => {
      stale = true
    }
  }, [tenantId])

  const records = list.data?.records ?? []
  const stdioEnabled = options?.stdioEnabled ?? false

  function reload() {
    list.reload()
  }

  async function toggle(server: Server) {
    await submit.run(async () => {
      const saved = await mcpApi.update(tenantId, server.id, { enabled: !server.enabled })
      if (!submit.active.current) return
      reload()
      return saved.enabled ? '服务已启用' : '服务已停用'
    }, '操作失败')
  }

  async function testSaved(server: Server) {
    await submit.run(async () => {
      const result = await mcpApi.testSaved(tenantId, server.id)
      if (!submit.active.current) return
      setProbe({ title: server.name, result })
      return undefined
    }, '连接测试失败')
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="MCP 服务"
        description="管理当前租户的 MCP Server；模板绑定与 Agent 接入在后续阶段提供。"
        actions={
          canManage && (
            <Button onClick={() => setEditor({ server: null })}>
              <Plus aria-hidden="true" />
              新建服务
            </Button>
          )
        }
      />
      {!canManage && (
        <p className="rounded-lg border border-border bg-surface-subtle p-3 text-sm text-muted-foreground">
          你可以查看当前租户的 MCP 服务，配置由负责人管理。
        </p>
      )}
      {!stdioEnabled && (
        <p className="rounded-lg border border-border bg-surface-subtle p-3 text-sm text-muted-foreground">
          stdio 传输由部署开关控制，当前未启用；远程 HTTP 服务不受影响。
        </p>
      )}
      {!list.loading && list.error && <ErrorState message={list.error} onRetry={reload} />}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-semibold">
          服务清单{' '}
          <span className="text-sm font-normal text-muted-foreground">{list.data?.total ?? 0}</span>
        </h2>
        <div className="flex items-center gap-2">
          <SearchInput
            value={query}
            onChange={(value) => {
              setQuery(value)
              list.setPage(1)
            }}
            label="搜索服务名称"
            placeholder="搜索服务名称"
          />
          <Button
            size="icon-sm"
            variant="ghost"
            aria-label="刷新服务"
            disabled={list.loading}
            onClick={reload}
          >
            <RefreshCw aria-hidden="true" />
          </Button>
        </div>
      </div>
      {list.loading && <output className="text-sm text-muted-foreground">正在加载服务…</output>}
      <div className="grid gap-4 md:grid-cols-2" aria-busy={list.loading}>
        {list.loading && !list.data
          ? [1, 2].map((index) => (
              <div key={index} className="h-40 rounded-lg border border-border bg-canvas p-4">
                <div className="h-4 w-28 rounded-sm bg-surface-subtle" />
                <div className="mt-3 h-3 w-40 rounded-sm bg-surface-subtle" />
                <div className="mt-4 h-3 w-24 rounded-sm bg-surface-subtle" />
              </div>
            ))
          : records.map((server) => {
              const stdioBlocked = server.transport === 'STDIO' && !stdioEnabled
              return (
                <article
                  key={server.id}
                  className="flex min-w-0 flex-col gap-3 rounded-lg border border-border bg-surface p-4"
                >
                  <div className="flex items-center justify-between gap-2">
                    <Badge variant="outline">{TRANSPORT_LABELS[server.transport]}</Badge>
                    <Badge variant={server.enabled ? 'success' : 'secondary'}>
                      {server.enabled ? '启用' : '停用'}
                    </Badge>
                  </div>
                  <div className="min-w-0">
                    <h3 className="truncate font-medium" title={server.name}>
                      {server.name}
                    </h3>
                    <p className="truncate font-mono text-xs text-muted-foreground">
                      mcp__{server.slug}__
                    </p>
                  </div>
                  <p
                    className="truncate font-mono text-xs text-text-secondary"
                    title={
                      server.transport === 'STDIO' ? (server.command ?? '') : (server.url ?? '')
                    }
                  >
                    {server.transport === 'STDIO'
                      ? [server.command, ...server.args].join(' ')
                      : server.url}
                  </p>
                  {stdioBlocked && (
                    <p className="text-xs text-warning">stdio 未启用：总开关关闭时不生效。</p>
                  )}
                  <div className="mt-auto flex flex-wrap items-center justify-between gap-2 border-t border-border pt-3 text-xs text-muted-foreground">
                    <span>
                      {server.authHeaders.length > 0 && `鉴权头 ${server.authHeaders.length} · `}
                      {server.toolTimeoutMs ? `超时 ${server.toolTimeoutMs}ms` : '超时随全局'}
                    </span>
                    {canManage && (
                      <span className="flex flex-wrap gap-1">
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={submit.busy}
                          onClick={() => void testSaved(server)}
                        >
                          测试连接
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={submit.busy}
                          onClick={() => setEditor({ server })}
                        >
                          编辑
                        </Button>
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={submit.busy}
                          onClick={() => void toggle(server)}
                        >
                          {server.enabled ? '停用' : '启用'}
                        </Button>
                        <DeleteRecord
                          name={server.name}
                          description="删除后不可恢复；模板绑定能力接入后将有绑定保护。"
                          disabled={submit.busy}
                          onDelete={() => mcpApi.delete(tenantId, server.id)}
                          onDeleted={reload}
                        />
                      </span>
                    )}
                  </div>
                </article>
              )
            })}
      </div>
      {!list.loading && !list.error && records.length === 0 && (
        <div className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
          {debouncedQuery
            ? '没有匹配的服务'
            : canManage
              ? '还没有 MCP 服务，点击新建服务登记一个远程 MCP Server。'
              : '负责人尚未配置 MCP 服务。'}
        </div>
      )}
      <ListPagination
        page={list.page}
        size={list.data?.size ?? 20}
        total={list.data?.total ?? 0}
        pending={list.loading || submit.busy}
        onPage={list.setPage}
      />
      {editor && options && (
        <ServerEditor
          tenantId={tenantId}
          server={editor.server}
          stdioEnabled={stdioEnabled}
          onClose={() => setEditor(null)}
          onSaved={() => {
            setEditor(null)
            toast.success(editor.server ? '服务已更新' : '服务已创建')
            reload()
          }}
        />
      )}
      <Dialog open={probe !== null} onOpenChange={(open) => !open && setProbe(null)}>
        <DialogContent className="max-h-[80vh] overflow-y-auto">
          <DialogTitle>连接测试 · {probe?.title}</DialogTitle>
          <DialogDescription>一次性握手与工具清单获取，不维持长连接。</DialogDescription>
          {probe && <ProbeResult result={probe.result} />}
        </DialogContent>
      </Dialog>
    </div>
  )
}
