import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { DeleteRecord } from '@/components/DeleteRecord'
import { ErrorState } from '@/components/ErrorState'
import { ListPagination } from '@/components/ListPagination'
import { SearchInput } from '@/components/SearchInput'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { usePagedList } from '@/lib/usePagedList'
import { useSubmitting } from '@/lib/useSubmitting'
import { llmApi, type Model, type Source, type Page } from './api'
import { ModelEditor } from './ModelEditor'
import { DiscoveryDialog } from './DiscoveryDialog'

export function ModelsPanel({
  tenantId,
  source,
  canManage,
  onSourceChanged,
}: {
  tenantId: number
  source: Source
  canManage: boolean
  onSourceChanged: () => void
}) {
  const submit = useSubmitting()
  const [query, setQuery] = useState('')
  const debouncedQuery = useDebouncedValue(query)
  const list = usePagedList(
    (page: number) => llmApi.models(tenantId, source.id, page, debouncedQuery),
    [tenantId, source.id, debouncedQuery],
  )
  const result: Page<Model> = list.data ?? { records: [], total: 0, page: 1, size: 20 }
  const [editor, setEditor] = useState<{ model: Model | null } | null>(null)
  const [discover, setDiscover] = useState(false)
  function changed() {
    list.reload()
    onSourceChanged()
  }
  async function toggle(model: Model) {
    await submit.run(async () => {
      await llmApi.updateModel(tenantId, source.id, model.id, { enabled: !model.enabled })
      if (!submit.active.current) return
      changed()
      return model.enabled ? '模型已停用' : '模型已启用'
    }, '操作失败')
  }
  const busy = submit.busy
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
            <Button disabled={busy} onClick={() => setEditor({ model: null })}>
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
      <SearchInput
        value={query}
        onChange={(value) => {
          setQuery(value)
          list.setPage(1)
        }}
        label="搜索已保存模型"
        placeholder="搜索模型名称或模型 ID"
        className="relative min-w-0"
      />
      {!list.loading && list.error && <ErrorState message={list.error} onRetry={list.reload} />}
      {list.loading && <output className="text-sm text-muted-foreground">正在加载模型…</output>}
      <div
        className="overflow-hidden rounded-lg border border-border bg-surface"
        aria-busy={list.loading}
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
            {list.loading && !list.data
              ? [1, 2, 3].map((index) => (
                  <TableRow key={index}>
                    <TableCell colSpan={canManage ? 4 : 3} className="py-4">
                      <div className="h-4 w-1/2 rounded-sm bg-surface-subtle" />
                      <div className="mt-2 h-3 w-1/3 rounded-sm bg-surface-subtle" />
                    </TableCell>
                  </TableRow>
                ))
              : result.records.map((model) => (
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
                      <div className="mt-1 text-xs text-muted-foreground">
                        {model.contextLimit
                          ? `${model.contextLimit.toLocaleString()} Token 上限`
                          : '上下文上限待补齐'}
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
                            disabled={busy || list.loading}
                            onClick={() => setEditor({ model })}
                          >
                            编辑
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={busy || list.loading}
                            onClick={() => void toggle(model)}
                          >
                            {model.enabled ? '停用' : '启用'}
                          </Button>
                          <DeleteRecord
                            name={model.name}
                            description="删除后无法恢复。若有模板引用，请改为停用。"
                            disabled={busy || list.loading}
                            onDelete={() => llmApi.deleteModel(tenantId, source.id, model.id)}
                            onDeleted={changed}
                          />
                        </div>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
            {!list.loading && !list.error && result.records.length === 0 && (
              <TableRow>
                <TableCell
                  colSpan={canManage ? 4 : 3}
                  className="py-10 text-center text-muted-foreground"
                >
                  {debouncedQuery ? '没有匹配的模型' : '此连接还没有模型'}
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
        <ListPagination
          page={list.page}
          size={result.size}
          total={result.total}
          pending={list.loading || busy}
          onPage={list.setPage}
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
