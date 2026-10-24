import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Plus, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'
import { ApiError } from '@/lib/api'
import { DeleteRecord } from '@/components/DeleteRecord'
import { ListPagination } from '@/components/ListPagination'
import { llmApi, type Page, type Source } from './api'
import { ConnectionEditor } from './ConnectionEditor'
import { ModelsPanel } from './ModelsPanel'

type LlmAccessProps = { tenantId: number; canManage: boolean }

export function LlmAccess(props: LlmAccessProps) {
  return <LlmAccessState key={props.tenantId} {...props} />
}

function LlmAccessState({ tenantId, canManage }: LlmAccessProps) {
  const active = useRef(true)
  const submitting = useRef(false)
  useEffect(() => {
    active.current = true
    return () => {
      active.current = false
    }
  }, [])
  const [result, setResult] = useState<Page<Source>>({ records: [], total: 0, page: 1, size: 20 })
  const [source, setSource] = useState<Source | null>(null)
  const [page, setPage] = useState(1)
  const [query, setQuery] = useState('')
  const [revision, setRevision] = useState(0)
  const [finished, setFinished] = useState('')
  const [error, setError] = useState('')
  const [sourceError, setSourceError] = useState('')
  const [busy, setBusy] = useState(false)
  const [editor, setEditor] = useState<{ source: Source | null } | null>(null)
  const requestKey = JSON.stringify([tenantId, page, query, revision])
  const loading = finished !== requestKey
  const sourceId = source?.id
  useEffect(() => {
    if (sourceId === undefined) return
    let stale = false
    void llmApi.source(tenantId, sourceId).then(
      (updated) => {
        if (!stale) {
          setSourceError('')
          setSource((current) => (current?.id === sourceId ? updated : current))
        }
      },
      (cause: unknown) => {
        if (stale) return
        if (cause instanceof ApiError && cause.status === 404) {
          setSource((current) => (current?.id === sourceId ? null : current))
          setEditor((current) => (current?.source?.id === sourceId ? null : current))
          setSourceError('选中的连接已删除，请重新选择')
        } else setSourceError(cause instanceof Error ? cause.message : '连接刷新失败')
      },
    )
    return () => {
      stale = true
    }
  }, [tenantId, sourceId, revision])
  useEffect(() => {
    let stale = false
    void llmApi
      .sources(tenantId, page, query)
      .then((next) => {
        if (!stale) {
          setError('')
          if (page > 1 && next.records.length === 0)
            setPage(Math.max(1, Math.ceil(next.total / next.size)))
          else {
            setResult(next)
            setSource(
              (current) =>
                next.records.find((item) => item.id === current?.id) ??
                current ??
                next.records[0] ??
                null,
            )
          }
        }
      })
      .catch((cause: unknown) => {
        if (!stale) setError(cause instanceof Error ? cause.message : '连接加载失败')
      })
      .finally(() => {
        if (!stale) setFinished(requestKey)
      })
    return () => {
      stale = true
    }
  }, [tenantId, page, query, revision, requestKey])
  function reload() {
    setRevision((n) => n + 1)
  }
  async function toggle(selected: Source) {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    try {
      const saved = await llmApi.updateSource(tenantId, selected.id, { enabled: !selected.enabled })
      if (!active.current) return
      setSource((current) => (current?.id === selected.id ? saved : current))
      reload()
      if (!active.current) return
      toast.success(saved.enabled ? '连接已启用' : '连接已停用')
    } catch (cause) {
      if (active.current) toast.error(cause instanceof Error ? cause.message : '操作失败')
    } finally {
      submitting.current = false
      if (active.current) setBusy(false)
    }
  }
  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">模型接入</h1>
          <p className="mt-2 text-sm text-muted-foreground">管理当前工作区的供应商连接和模型。</p>
        </div>
        {canManage && (
          <Button data-admin-add onClick={() => setEditor({ source: null })}>
            <Plus aria-hidden="true" />
            新增连接
          </Button>
        )}
      </div>
      {!canManage && (
        <p className="rounded-lg border border-border bg-surface-subtle p-3 text-sm text-muted-foreground">
          你可以查看当前工作区的连接和模型，配置由负责人管理。
        </p>
      )}
      {!loading && (error || sourceError) && (
        <div role="alert" className="flex items-center justify-between gap-3 text-sm text-danger">
          <span>{error || sourceError}</span>
          <Button variant="outline" onClick={reload}>
            重试
          </Button>
        </div>
      )}
      <div className="grid min-w-0 gap-6 lg:grid-cols-[280px_minmax(0,1fr)]">
        <section aria-label="供应商连接" className="min-w-0 space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="font-semibold">
              供应商连接{' '}
              <span className="text-sm font-normal text-muted-foreground">{result.total}</span>
            </h2>
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="刷新连接"
              disabled={loading}
              onClick={reload}
            >
              <RefreshCw aria-hidden="true" />
            </Button>
          </div>
          <Input
            aria-label="搜索连接名称"
            placeholder="搜索连接名称"
            maxLength={160}
            value={query}
            onChange={(e) => {
              setQuery(e.target.value)
              setPage(1)
            }}
          />
          {loading && <output className="text-sm text-muted-foreground">正在加载连接…</output>}
          <div className="space-y-2" aria-busy={loading}>
            {result.records.map((item) => (
              <button
                key={item.id}
                type="button"
                disabled={busy}
                aria-pressed={source?.id === item.id}
                onClick={() => setSource(item)}
                className={cn(
                  'grid w-full gap-2 rounded-lg border border-border bg-surface p-4 text-left text-sm outline-none hover:bg-surface-hover focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50',
                  source?.id === item.id && 'border-accent-border bg-accent-soft',
                )}
              >
                <span className="flex min-w-0 items-center justify-between gap-2">
                  <span className="truncate font-medium" title={item.name}>
                    {item.name}
                  </span>
                  <Badge variant={item.enabled ? 'success' : 'secondary'}>
                    {item.enabled ? '启用' : '停用'}
                  </Badge>
                </span>
                <span className="truncate text-muted-foreground" title={item.supplierName}>
                  {item.supplierName}
                </span>
                <span className="flex flex-wrap justify-between gap-2 text-xs text-muted-foreground">
                  <span>
                    {item.protocol === 'OPENAI_COMPATIBLE' ? 'OpenAI 兼容' : 'Anthropic 兼容'}
                  </span>
                  <span>{item.modelCount} 个模型</span>
                </span>
              </button>
            ))}
          </div>
          {!loading && !error && result.records.length === 0 && (
            <div className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
              {query
                ? '没有匹配的连接'
                : canManage
                  ? '还没有连接，点击新增连接开始配置。'
                  : '负责人尚未配置连接。'}
            </div>
          )}
          <ListPagination
            page={page}
            size={20}
            total={result.total}
            pending={loading || busy}
            onPage={setPage}
          />
        </section>
        {source ? (
          <section aria-label="连接详情" className="min-w-0 space-y-6">
            <div className="space-y-4 rounded-lg border border-border bg-surface p-4 md:p-6">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <h2 className="text-xl font-semibold break-words">{source.name}</h2>
                  <p className="mt-1 text-sm break-words text-muted-foreground">
                    {source.supplierName} ·{' '}
                    {source.protocol === 'OPENAI_COMPATIBLE' ? 'OpenAI 兼容' : 'Anthropic 兼容'}
                  </p>
                </div>
                {canManage && (
                  <div className="flex flex-wrap gap-1">
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={busy}
                      onClick={() => setEditor({ source })}
                    >
                      编辑连接
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={busy}
                      onClick={() => void toggle(source)}
                    >
                      {source.enabled ? '停用连接' : '启用连接'}
                    </Button>
                    <DeleteRecord
                      key={source.id}
                      name={source.name}
                      description="将同时删除该连接下的全部模型。若有模板引用，请改为停用。"
                      disabled={busy}
                      onDelete={() => llmApi.deleteSource(tenantId, source.id)}
                      onDeleted={() => {
                        setSource(null)
                        reload()
                      }}
                    />
                  </div>
                )}
              </div>
              <p className="text-sm break-all text-muted-foreground">{source.baseUrl}</p>
              <div className="flex flex-wrap gap-2">
                <Badge variant={source.enabled ? 'success' : 'secondary'}>
                  {source.enabled ? '连接启用' : '连接停用'}
                </Badge>
                <Badge variant="outline">
                  {source.hasApiKey ? 'API Key 已配置' : 'API Key 未配置'}
                </Badge>
              </div>
              {!source.enabled && (
                <p className="text-sm text-muted-foreground">
                  连接已停用，仍可维护模型。重新启用不会改变各模型自身状态。
                </p>
              )}
            </div>
            <ModelsPanel
              key={source.id}
              tenantId={tenantId}
              source={source}
              canManage={canManage}
              onSourceChanged={reload}
            />
          </section>
        ) : (
          <div className="flex min-h-64 items-center justify-center rounded-lg border border-dashed border-border p-6 text-sm text-muted-foreground">
            选择一个连接以查看模型
          </div>
        )}
      </div>
      {editor && (
        <ConnectionEditor
          tenantId={tenantId}
          source={editor.source}
          onClose={() => setEditor(null)}
          onSaved={(saved) => {
            setEditor(null)
            setSource(saved)
            reload()
          }}
        />
      )}
    </div>
  )
}
