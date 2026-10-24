import { useEffect, useState } from 'react'
import { Plus, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { ApiError } from '@/lib/api'
import { errorMessage } from '@/lib/errors'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { usePagedList } from '@/lib/usePagedList'
import { useSubmitting } from '@/lib/useSubmitting'
import { DeleteRecord } from '@/components/DeleteRecord'
import { ErrorState } from '@/components/ErrorState'
import { ListPagination } from '@/components/ListPagination'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { llmApi, type Page, type Source } from './api'
import { ConnectionEditor } from './ConnectionEditor'
import { ModelsPanel } from './ModelsPanel'

export function LlmAccess({ tenantId, canManage }: { tenantId: number; canManage: boolean }) {
  const submit = useSubmitting()
  const [query, setQuery] = useState('')
  const debouncedQuery = useDebouncedValue(query)
  const list = usePagedList(
    (page: number) => llmApi.sources(tenantId, page, debouncedQuery),
    [tenantId, debouncedQuery],
  )
  const result: Page<Source> = list.data ?? { records: [], total: 0, page: 1, size: 20 }
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [sourceDetail, setSourceDetail] = useState<Source | null>(null)
  const [sourceError, setSourceError] = useState('')
  const [detailRevision, setDetailRevision] = useState(0)
  const [editor, setEditor] = useState<{ source: Source | null } | null>(null)
  // 选中项在渲染时派生：用户选择 > 最近一次详情（默认选中在首次详情后固化，筛选隐藏也不丢）> 列表第一条。
  const effectiveId = selectedId ?? sourceDetail?.id ?? result.records[0]?.id ?? null
  const source =
    sourceDetail?.id === effectiveId
      ? sourceDetail
      : (result.records.find((item) => item.id === effectiveId) ?? null)
  useEffect(() => {
    if (effectiveId === null) return
    let stale = false
    void llmApi.source(tenantId, effectiveId).then(
      (updated) => {
        if (!stale) {
          setSourceError('')
          setSourceDetail((current) =>
            current === null || current.id === effectiveId ? updated : current,
          )
        }
      },
      (cause: unknown) => {
        if (stale) return
        if (cause instanceof ApiError && cause.status === 404) {
          setSourceDetail((current) => (current?.id === effectiveId ? null : current))
          setEditor((current) => (current?.source?.id === effectiveId ? null : current))
          setSourceError('选中的连接已删除，请重新选择')
        } else setSourceError(errorMessage(cause, '连接刷新失败'))
      },
    )
    return () => {
      stale = true
    }
  }, [tenantId, effectiveId, detailRevision])
  function reload() {
    list.reload()
    setDetailRevision((value) => value + 1)
  }
  async function toggle(selected: Source) {
    await submit.run(async () => {
      const saved = await llmApi.updateSource(tenantId, selected.id, {
        enabled: !selected.enabled,
      })
      if (!submit.active.current) return
      setSourceDetail((current) => (current?.id === selected.id ? saved : current))
      reload()
      return saved.enabled ? '连接已启用' : '连接已停用'
    }, '操作失败')
  }
  const busy = submit.busy
  return (
    <div className="space-y-6">
      <PageHeader
        title="模型接入"
        description="管理当前租户的供应商连接和模型。"
        actions={
          canManage && (
            <Button onClick={() => setEditor({ source: null })}>
              {' '}
              <Plus aria-hidden="true" />
              新增连接
            </Button>
          )
        }
      />
      {!canManage && (
        <p className="rounded-lg border border-border bg-surface-subtle p-3 text-sm text-muted-foreground">
          你可以查看当前租户的连接和模型，配置由负责人管理。
        </p>
      )}
      {!list.loading && (list.error || sourceError) && (
        <ErrorState message={list.error || sourceError} onRetry={reload} />
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
              disabled={list.loading}
              onClick={reload}
            >
              <RefreshCw aria-hidden="true" />
            </Button>
          </div>
          <SearchInput
            value={query}
            onChange={(value) => {
              setQuery(value)
              list.setPage(1)
            }}
            label="搜索连接名称"
            placeholder="搜索连接名称"
            className="relative min-w-0"
          />
          {list.loading && <output className="text-sm text-muted-foreground">正在加载连接…</output>}
          <div className="space-y-2" aria-busy={list.loading}>
            {list.loading && !list.data
              ? [1, 2, 3].map((index) => (
                  <div
                    key={index}
                    className="h-[104px] rounded-lg border border-border bg-canvas p-4"
                  >
                    <div className="h-4 w-24 rounded-sm bg-surface-subtle" />
                    <div className="mt-3 h-3 w-32 rounded-sm bg-surface-subtle" />
                    <div className="mt-4 h-3 w-20 rounded-sm bg-surface-subtle" />
                  </div>
                ))
              : result.records.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    disabled={busy}
                    aria-pressed={source?.id === item.id}
                    onClick={() => setSelectedId(item.id)}
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
          {!list.loading && !list.error && result.records.length === 0 && (
            <div className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
              {debouncedQuery
                ? '没有匹配的连接'
                : canManage
                  ? '还没有连接，点击新增连接开始配置。'
                  : '负责人尚未配置连接。'}
            </div>
          )}
          <ListPagination
            page={list.page}
            size={result.size}
            total={result.total}
            pending={list.loading || busy}
            onPage={list.setPage}
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
                        setSelectedId(null)
                        setSourceDetail(null)
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
            setSelectedId(saved.id)
            setSourceDetail(saved)
            reload()
          }}
        />
      )}
    </div>
  )
}
