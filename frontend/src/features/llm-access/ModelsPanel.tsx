import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { DeleteRecord } from '@/components/DeleteRecord'
import { ListPagination } from '@/components/ListPagination'
import { llmApi, type Model, type Source, type Page } from './api'
import { ModelEditor } from './ModelEditor'
import { DiscoveryDialog } from './DiscoveryDialog'

type ModelsPanelProps = {
  tenantId: number
  source: Source
  canManage: boolean
  onSourceChanged: () => void
}

export function ModelsPanel(props: ModelsPanelProps) {
  return <ModelsPanelState key={`${props.tenantId}:${props.source.id}`} {...props} />
}

function ModelsPanelState({ tenantId, source, canManage, onSourceChanged }: ModelsPanelProps) {
  const active = useRef(true)
  const submitting = useRef(false)
  useEffect(() => {
    active.current = true
    return () => {
      active.current = false
    }
  }, [])
  const [page, setPage] = useState(1)
  const [query, setQuery] = useState('')
  const [result, setResult] = useState<Page<Model>>({ records: [], total: 0, page: 1, size: 20 })
  const [finished, setFinished] = useState('')
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [busy, setBusy] = useState(false)
  const [editor, setEditor] = useState<{ model: Model | null } | null>(null)
  const [discover, setDiscover] = useState(false)
  const requestKey = JSON.stringify([tenantId, source.id, page, query, revision])
  const loading = finished !== requestKey
  useEffect(() => {
    let stale = false
    void llmApi
      .models(tenantId, source.id, page, query)
      .then((next) => {
        if (!stale) {
          setError('')
          if (page > 1 && next.records.length === 0)
            setPage(Math.max(1, Math.ceil(next.total / next.size)))
          else setResult(next)
        }
      })
      .catch((cause: unknown) => {
        if (!stale) setError(cause instanceof Error ? cause.message : '模型加载失败')
      })
      .finally(() => {
        if (!stale) setFinished(requestKey)
      })
    return () => {
      stale = true
    }
  }, [tenantId, source.id, page, query, revision, requestKey])
  function changed() {
    setRevision((n) => n + 1)
    onSourceChanged()
  }
  async function toggle(model: Model) {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    try {
      await llmApi.updateModel(tenantId, source.id, model.id, { enabled: !model.enabled })
      if (!active.current) return
      toast.success(model.enabled ? '模型已停用' : '模型已启用')
      changed()
    } catch (cause) {
      if (active.current) toast.error(cause instanceof Error ? cause.message : '操作失败')
    } finally {
      submitting.current = false
      if (active.current) setBusy(false)
    }
  }
  return (
    <div className="min-w-0 space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h3 className="font-semibold">
          模型{' '}
          <span className="text-sm font-normal text-muted-foreground">{source.modelCount} 个</span>
        </h3>
        {canManage && (
          <div className="flex flex-wrap gap-2">
            <Button
              id={`discover-${source.id}`}
              variant="outline"
              disabled={busy || !source.hasApiKey}
              onClick={() => setDiscover(true)}
            >
              查询供应商模型
            </Button>
            <Button data-admin-add disabled={busy} onClick={() => setEditor({ model: null })}>
              手动添加
            </Button>
          </div>
        )}
      </div>
      {canManage && !source.hasApiKey && (
        <p className="text-sm text-muted-foreground">
          尚未配置 API Key，完善连接后可查询候选；也可先手动添加模型。
        </p>
      )}
      <Input
        aria-label="搜索已保存模型"
        placeholder="搜索模型名称或模型 ID"
        maxLength={160}
        value={query}
        onChange={(e) => {
          setQuery(e.target.value)
          setPage(1)
        }}
      />
      {!loading && error && (
        <div role="alert" className="flex items-center justify-between gap-2 text-sm text-danger">
          <span>{error}</span>
          <Button variant="outline" onClick={() => setRevision((n) => n + 1)}>
            重试
          </Button>
        </div>
      )}
      {loading && <output className="text-sm text-muted-foreground">正在加载模型…</output>}
      <div
        className="overflow-hidden rounded-lg border border-border bg-surface"
        aria-busy={loading}
      >
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>模型</TableHead>
              <TableHead>添加方式</TableHead>
              <TableHead>状态</TableHead>
              {canManage && <TableHead className="text-right">操作</TableHead>}
            </TableRow>
          </TableHeader>
          <TableBody>
            {result.records.map((model) => (
              <TableRow key={model.id}>
                <TableCell>
                  <div className="max-w-64 truncate" title={model.name}>
                    {model.name}
                  </div>
                  <div
                    className="max-w-64 truncate font-mono text-xs text-muted-foreground"
                    title={model.modelId}
                  >
                    {model.modelId}
                  </div>
                </TableCell>
                <TableCell className="whitespace-nowrap">
                  {model.origin === 'MANUAL' ? '手动添加' : '查询导入'}
                </TableCell>
                <TableCell>
                  <Badge variant={model.enabled ? 'success' : 'secondary'}>
                    {model.enabled ? '启用' : '停用'}
                  </Badge>
                </TableCell>
                {canManage && (
                  <TableCell>
                    <div className="flex justify-end gap-1">
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy || loading}
                        onClick={() => setEditor({ model })}
                      >
                        编辑
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy || loading}
                        onClick={() => void toggle(model)}
                      >
                        {model.enabled ? '停用' : '启用'}
                      </Button>
                      <DeleteRecord
                        name={model.name}
                        description="删除后无法恢复。若有模板引用，请改为停用。"
                        disabled={busy || loading}
                        onDelete={() => llmApi.deleteModel(tenantId, source.id, model.id)}
                        onDeleted={changed}
                      />
                    </div>
                  </TableCell>
                )}
              </TableRow>
            ))}
            {!loading && !error && result.records.length === 0 && (
              <TableRow>
                <TableCell
                  colSpan={canManage ? 4 : 3}
                  className="py-10 text-center text-muted-foreground"
                >
                  {query ? '没有匹配的模型' : '此连接还没有模型'}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
        <ListPagination
          page={page}
          size={20}
          total={result.total}
          pending={loading || busy}
          onPage={setPage}
        />
      </div>
      {editor && (
        <ModelEditor
          tenantId={tenantId}
          sourceId={source.id}
          model={editor.model}
          onClose={() => setEditor(null)}
          onSaved={() => {
            setEditor(null)
            changed()
          }}
        />
      )}
      {discover && (
        <DiscoveryDialog
          tenantId={tenantId}
          source={source}
          onClose={() => setDiscover(false)}
          onImported={() => {
            setDiscover(false)
            changed()
          }}
        />
      )}
    </div>
  )
}
