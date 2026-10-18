import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { llmApi, type Candidate, type Source } from './api'

export function DiscoveryDialog({
  tenantId,
  source,
  onClose,
  onImported,
}: {
  tenantId: number
  source: Source
  onClose: () => void
  onImported: () => void
}) {
  const [records, setRecords] = useState<Candidate[]>([])
  const [existing, setExisting] = useState(new Set<string>())
  const [selected, setSelected] = useState(new Set<string>())
  const [query, setQuery] = useState('')
  const [cursor, setCursor] = useState<string | null>(null)
  const [supported, setSupported] = useState(true)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const active = useRef(true)
  const visited = useRef(new Set<string>())
  useEffect(() => {
    active.current = true
    let stale = false
    void Promise.all([
      llmApi.discover(tenantId, source.id),
      llmApi.existingIds(tenantId, source.id),
    ])
      .then(([result, ids]) => {
        if (!stale) {
          setRecords(result.records)
          setCursor(result.nextCursor)
          setSupported(result.supported)
          setExisting(ids)
        }
      })
      .catch((cause: unknown) => {
        if (!stale) setError(cause instanceof Error ? cause.message : '查询失败')
      })
      .finally(() => {
        if (!stale) setLoading(false)
      })
    return () => {
      stale = true
      active.current = false
    }
  }, [tenantId, source.id])
  async function loadMore(restart = false) {
    if (loading) return
    const next = restart ? undefined : (cursor ?? undefined)
    if (!restart && next && visited.current.has(next)) {
      setError('供应商分页重复，请重新查询')
      return
    }
    setLoading(true)
    setError('')
    try {
      const [result, ids] = await Promise.all([
        llmApi.discover(tenantId, source.id, next),
        llmApi.existingIds(tenantId, source.id),
      ])
      if (!active.current) return
      if (restart) {
        visited.current.clear()
        setSelected(new Set())
      }
      if (next) visited.current.add(next)
      setRecords((old) => [
        ...new Map(
          (restart ? result.records : [...old, ...result.records]).map((item) => [
            item.modelId,
            item,
          ]),
        ).values(),
      ])
      setCursor(result.nextCursor)
      setSupported(result.supported)
      setExisting(ids)
    } catch (cause) {
      if (active.current) setError(cause instanceof Error ? cause.message : '查询失败')
    } finally {
      if (active.current) setLoading(false)
    }
  }
  async function save() {
    if (saving || selected.size === 0) return
    setSaving(true)
    setError('')
    try {
      const result = await llmApi.importModels(
        tenantId,
        source.id,
        records.filter((item) => selected.has(item.modelId) && !existing.has(item.modelId)),
      )
      if (!active.current) return
      toast.success(
        `已添加 ${result.created.length} 个模型，跳过 ${result.skippedModelIds.length} 个已有模型`,
      )
      onImported()
    } catch (cause) {
      if (active.current) setError(cause instanceof Error ? cause.message : '导入失败')
    } finally {
      if (active.current) setSaving(false)
    }
  }
  const filtered = records.filter((item) =>
    `${item.name} ${item.modelId}`.toLowerCase().includes(query.toLowerCase()),
  )
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !saving) onClose()
      }}
    >
      <DialogContent
        onCloseAutoFocus={(event) => {
          event.preventDefault()
          document.getElementById(`discover-${source.id}`)?.focus()
        }}
      >
        <DialogTitle className="pr-6 font-semibold">查询供应商模型 · {source.name}</DialogTitle>
        <DialogDescription className="text-sm text-muted-foreground">
          候选不会自动保存。勾选后添加，每次最多 100 个，已有模型保持原状态。
        </DialogDescription>
        <Input
          aria-label="搜索候选模型"
          placeholder="搜索名称或模型 ID"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {loading && <output className="text-sm text-muted-foreground">正在查询供应商…</output>}
        {!supported && (
          <p className="text-sm text-muted-foreground">
            此服务不支持模型列表，请关闭后手动添加模型。
          </p>
        )}
        {error && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
        <div className="max-h-80 overflow-y-auto rounded-lg border border-border">
          {filtered.map((item) => (
            <Label
              key={item.modelId}
              className="flex items-center gap-3 border-b border-border p-3 last:border-b-0"
            >
              <Checkbox
                disabled={
                  saving ||
                  existing.has(item.modelId) ||
                  (!selected.has(item.modelId) && selected.size >= 100)
                }
                checked={selected.has(item.modelId) || existing.has(item.modelId)}
                onCheckedChange={(checked) =>
                  setSelected((old) => {
                    const next = new Set(old)
                    if (checked === true) next.add(item.modelId)
                    else next.delete(item.modelId)
                    return next
                  })
                }
              />
              <span className="min-w-0 flex-1">
                <span className="block truncate">{item.name}</span>
                <span className="block truncate font-mono text-xs text-muted-foreground">
                  {item.modelId}
                </span>
              </span>
              {existing.has(item.modelId) && (
                <span className="text-xs text-muted-foreground">已添加</span>
              )}
            </Label>
          ))}
          {!loading && supported && filtered.length === 0 && (
            <p className="p-4 text-sm text-muted-foreground">
              {error ? '查询未完成，可重试或手动添加。' : '没有匹配的候选模型。'}
            </p>
          )}
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex gap-2">
            <Button
              variant="outline"
              disabled={loading || saving}
              onClick={() => void loadMore(true)}
            >
              重新查询
            </Button>
            {cursor && (
              <Button
                variant="outline"
                disabled={loading || saving || records.length >= 2000}
                onClick={() => void loadMore()}
              >
                加载更多
              </Button>
            )}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" disabled={saving} onClick={onClose}>
              关闭
            </Button>
            <Button disabled={loading || saving || selected.size === 0} onClick={() => void save()}>
              {saving ? '添加中…' : `添加所选 (${selected.size})`}
            </Button>
          </div>
        </div>
      </DialogContent>
    </Dialog>
  )
}
