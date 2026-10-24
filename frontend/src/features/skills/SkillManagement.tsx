import { useEffect, useState } from 'react'
import { FileText, LayoutGrid, List, Plus, Search, Upload } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { ListPagination } from '@/components/ListPagination'
import { cn } from '@/lib/utils'
import { skillApi, type PackagePreview, type SkillPage, type SkillOrigin, type Skill } from './api'
import { SkillCreator } from './SkillCreator'
import { SkillDetail } from './SkillDetail'
import { SkillImportDialog } from './SkillImportDialog'

type SkillManagementProps = {
  tenantId: number
  canManageBuiltIn: boolean
  onDirtyChange: (dirty: boolean) => void
  onPendingChange?: (pending: boolean) => void
}

export function SkillManagement(props: SkillManagementProps) {
  return <SkillManagementState key={props.tenantId} {...props} />
}

function SkillManagementState({
  tenantId,
  canManageBuiltIn,
  onDirtyChange,
  onPendingChange,
}: SkillManagementProps) {
  const [result, setResult] = useState<SkillPage>({
    records: [],
    total: 0,
    page: 1,
    size: 12,
    counts: { custom: 0, system: 0 },
  })
  const [page, setPage] = useState(1)
  const [query, setQuery] = useState('')
  const [origin, setOrigin] = useState<SkillOrigin>('all')
  const [revision, setRevision] = useState(0)
  const [finished, setFinished] = useState('')
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<Skill | null>(null)
  const [create, setCreate] = useState<PackagePreview | 'empty' | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const [importing, setImporting] = useState(false)
  const [view, setView] = useState<'grid' | 'list'>('grid')
  useEffect(() => {
    onPendingChange?.(importing)
    return () => onPendingChange?.(false)
  }, [importing, onPendingChange])
  const key = JSON.stringify([tenantId, page, query, origin, revision])
  const loading = key !== finished
  useEffect(() => {
    let active = true
    void skillApi
      .list(tenantId, page, query, origin)
      .then(
        (value) => {
          if (!active) return
          setError('')
          if (page > 1 && !value.records.length)
            setPage(Math.max(1, Math.ceil(value.total / value.size)))
          else setResult(value)
        },
        (cause: unknown) => {
          if (active) setError(cause instanceof Error ? cause.message : 'Skill 加载失败')
        },
      )
      .finally(() => {
        if (active) setFinished(key)
      })
    return () => {
      active = false
    }
  }, [tenantId, page, query, origin, revision, key])
  function back() {
    setSelected(null)
    setCreate(null)
    onDirtyChange(false)
    setRevision((n) => n + 1)
  }
  function clear() {
    setQuery('')
    setOrigin('all')
    setPage(1)
  }
  const total = result.counts.custom + result.counts.system
  const filtered = !!query || origin !== 'all'
  const actions = (
    <>
      <Button variant="outline" disabled={importing} onClick={() => setImportOpen(true)}>
        <Upload aria-hidden />
        导入 Skill
      </Button>
      <Button data-admin-add disabled={importing} onClick={() => setCreate('empty')}>
        <Plus aria-hidden />
        新建 Skill
      </Button>
    </>
  )
  return (
    <div className="mx-auto w-full max-w-[1440px] space-y-6">
      <Tabs value="local">
        <TabsList aria-label="Skill 范围" className="gap-8">
          <TabsTrigger value="local" className="text-xl">
            本地
          </TabsTrigger>
          <TabsTrigger value="market" disabled className="text-xl">
            市场
            <Badge variant="outline" className="font-normal">
              即将开放
            </Badge>
          </TabsTrigger>
        </TabsList>
        <TabsContent value="local" className="pt-6">
          {create !== null ? (
            <SkillCreator
              tenantId={tenantId}
              imported={create === 'empty' ? null : create}
              onClose={back}
              onDirtyChange={onDirtyChange}
              onPendingChange={onPendingChange}
              onSaved={(saved) => {
                setCreate(null)
                setSelected(saved)
                onDirtyChange(false)
                setRevision((n) => n + 1)
              }}
            />
          ) : selected ? (
            <SkillDetail
              key={selected.id}
              tenantId={tenantId}
              skill={selected}
              canManage={selected.origin === 'CUSTOM' || canManageBuiltIn}
              onBack={back}
              onUpdated={(saved) => {
                setSelected(saved)
                setRevision((n) => n + 1)
              }}
              onDirtyChange={onDirtyChange}
              onPendingChange={onPendingChange}
            />
          ) : (
            <div className="space-y-5">
              <div className="flex flex-wrap items-center justify-between gap-5">
                <div>
                  <h1 className="flex items-center gap-3 text-2xl font-semibold">
                    Skill 管理
                    <Badge variant="secondary">{loading && !finished ? '—' : total}</Badge>
                  </h1>
                  <p className="mt-2 text-sm text-muted-foreground">
                    沉淀可复用的工作方法，让每一次任务都有章可循。
                  </p>
                </div>
                <div className="flex gap-2">{actions}</div>
              </div>
              <Tabs
                value={origin}
                onValueChange={(value) => {
                  if (value === 'all' || value === 'CUSTOM' || value === 'SYSTEM') {
                    setOrigin(value)
                    setPage(1)
                  }
                }}
              >
                <div className="flex flex-wrap items-end justify-between gap-x-5 gap-y-3 border-b border-border">
                  <TabsList aria-label="Skill 来源" className="order-2 border-0 md:order-1">
                    <TabsTrigger value="all">
                      全部<span className="text-xs font-normal text-muted-foreground">{total}</span>
                    </TabsTrigger>
                    <TabsTrigger value="CUSTOM">
                      自建
                      <span className="text-xs font-normal text-muted-foreground">
                        {result.counts.custom}
                      </span>
                    </TabsTrigger>
                    <TabsTrigger value="SYSTEM">
                      内置
                      <span className="text-xs font-normal text-muted-foreground">
                        {result.counts.system}
                      </span>
                    </TabsTrigger>
                  </TabsList>
                  <div className="order-1 flex w-full items-center gap-2 pb-3 md:order-2 md:w-auto">
                    <div className="relative min-w-0 flex-1 md:w-72">
                      <Search
                        className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
                        aria-hidden
                      />
                      <Input
                        className="pl-9"
                        aria-label="搜索 Skill"
                        placeholder="搜索名称、标识、描述或标签"
                        maxLength={160}
                        value={query}
                        onChange={(e) => {
                          setQuery(e.target.value)
                          setPage(1)
                        }}
                      />
                    </div>
                    <fieldset
                      aria-label="列表布局"
                      className="flex gap-1 rounded-md border border-border p-1"
                    >
                      <Button
                        variant={view === 'grid' ? 'secondary' : 'ghost'}
                        size="icon-sm"
                        aria-label="卡片视图"
                        aria-pressed={view === 'grid'}
                        onClick={() => setView('grid')}
                      >
                        <LayoutGrid aria-hidden />
                      </Button>
                      <Button
                        variant={view === 'list' ? 'secondary' : 'ghost'}
                        size="icon-sm"
                        aria-label="列表视图"
                        aria-pressed={view === 'list'}
                        onClick={() => setView('list')}
                      >
                        <List aria-hidden />
                      </Button>
                    </fieldset>
                  </div>
                </div>
                <TabsContent value={origin} className="space-y-4 pt-4">
                  <div className="flex justify-between gap-3 text-xs text-muted-foreground">
                    <span aria-live="polite">
                      {loading
                        ? '正在加载…'
                        : filtered
                          ? `找到 ${result.total} 个 Skill`
                          : `共 ${result.total} 个 Skill`}
                    </span>
                    <span className="hidden sm:inline">名称、描述与内容版本独立管理</span>
                  </div>
                  {error && !loading ? (
                    <div
                      role="alert"
                      className="rounded-lg border border-border bg-canvas p-8 text-center"
                    >
                      <h2 className="text-base font-medium">暂时无法加载 Skill</h2>
                      <p className="my-3 text-sm text-danger">{error}</p>
                      <Button variant="outline" onClick={() => setRevision((n) => n + 1)}>
                        重新加载
                      </Button>
                    </div>
                  ) : loading ? (
                    <div
                      aria-busy="true"
                      aria-label="正在加载 Skill"
                      className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3"
                    >
                      {[1, 2, 3].map((n) => (
                        <div key={n} className="h-56 rounded-lg border border-border bg-canvas p-4">
                          <div className="h-3 w-20 rounded-sm bg-surface-subtle" />
                          <div className="mt-4 h-4 w-32 rounded-sm bg-surface-subtle" />
                          <div className="mt-6 h-12 rounded-sm bg-surface-subtle" />
                        </div>
                      ))}
                    </div>
                  ) : result.records.length ? (
                    <div
                      className={cn(
                        view === 'grid'
                          ? 'grid gap-4 sm:grid-cols-2 xl:grid-cols-3'
                          : 'divide-y divide-border overflow-hidden rounded-lg border border-border',
                      )}
                    >
                      {result.records.map((skill) => (
                        <Card
                          key={skill.id}
                          density="compact"
                          className={cn(
                            'relative gap-3 transition-colors focus-within:border-border-strong hover:border-border-strong',
                            view === 'list' &&
                              'rounded-none border-0 md:grid md:grid-cols-[minmax(160px,1fr)_minmax(0,1.5fr)_auto] md:items-center md:gap-x-6',
                          )}
                        >
                          <Button
                            variant="link"
                            className="absolute inset-0 z-10 h-full w-full rounded-lg p-0"
                            aria-label={`查看 ${skill.name} 详情`}
                            onClick={() => setSelected(skill)}
                          >
                            <span className="sr-only">查看 {skill.name} 详情</span>
                          </Button>
                          <div
                            className={cn(
                              'flex items-center justify-between gap-3 text-xs',
                              view === 'list' && 'md:col-start-1 md:row-start-2 md:justify-start',
                            )}
                          >
                            <span className="text-muted-foreground">
                              {skill.origin === 'SYSTEM' ? '系统内置' : '工作区自建'}
                            </span>
                            <span
                              className={cn(
                                'inline-flex items-center gap-1.5',
                                skill.enabled ? 'text-success' : 'text-muted-foreground',
                              )}
                            >
                              <span className="size-1.5 rounded-full bg-current" />
                              {skill.enabled ? '已启用' : '已禁用'}
                            </span>
                          </div>
                          <div
                            className={cn(
                              'min-w-0',
                              view === 'list' && 'md:col-start-1 md:row-start-1',
                            )}
                          >
                            <h2 className="truncate text-base font-semibold" title={skill.name}>
                              {skill.name}
                            </h2>
                            <p
                              className="mt-1 truncate font-mono text-xs text-muted-foreground"
                              title={skill.slug}
                            >
                              {skill.slug}
                            </p>
                          </div>
                          <p
                            className={cn(
                              'line-clamp-2 min-h-12 text-sm leading-6 text-secondary',
                              view === 'list' &&
                                'md:col-start-2 md:row-span-2 md:row-start-1 md:min-h-0',
                            )}
                          >
                            {skill.description}
                          </p>
                          <div
                            className={cn(
                              'flex flex-wrap gap-2',
                              view === 'list' && 'md:col-span-2 md:row-start-3',
                            )}
                          >
                            {skill.tags.map((tag) => (
                              <Badge
                                key={tag}
                                variant="secondary"
                                className="max-w-full break-all whitespace-normal"
                              >
                                {tag}
                              </Badge>
                            ))}
                          </div>
                          <div
                            className={cn(
                              'mt-auto flex items-center justify-between gap-2 border-t border-border pt-3 text-xs text-muted-foreground',
                              view === 'list' &&
                                'md:col-start-3 md:row-span-3 md:row-start-1 md:mt-0 md:flex-col md:items-end md:border-0 md:pt-0',
                            )}
                          >
                            <span className="font-mono">
                              v{skill.latestVersion} · {skill.fileCount} 个文件
                            </span>
                            <span title={new Date(skill.contentUpdatedAt).toLocaleString()}>
                              {new Date(skill.contentUpdatedAt).toLocaleDateString('zh-CN', {
                                month: '2-digit',
                                day: '2-digit',
                              })}{' '}
                              内容更新
                            </span>
                          </div>
                        </Card>
                      ))}
                    </div>
                  ) : (
                    <div className="flex min-h-80 flex-col items-center justify-center rounded-lg border border-border bg-canvas p-8 text-center">
                      <span className="mb-5 rounded-lg border border-border bg-surface p-3">
                        <FileText className="size-6 text-secondary" aria-hidden />
                      </span>
                      <h2 className="text-xl font-semibold">
                        {filtered ? '没有找到匹配的 Skill' : '创建你的第一个 Skill'}
                      </h2>
                      <p className="mt-3 max-w-sm text-sm text-muted-foreground">
                        {filtered
                          ? '换个关键词，或清除筛选后查看全部 Skill。'
                          : '从一份清晰的指令开始，把常用的工作方法保存下来。'}
                      </p>
                      {filtered && (
                        <Button variant="outline" className="mt-6" onClick={clear}>
                          清除筛选
                        </Button>
                      )}
                      {!filtered && (
                        <span className="mt-6 text-xs text-muted-foreground">
                          支持 SKILL.md 与配套文件 · 内容按版本保存
                        </span>
                      )}
                    </div>
                  )}
                  {!loading && !error && result.total > 0 && (
                    <ListPagination
                      page={page}
                      size={12}
                      total={result.total}
                      pending={loading}
                      onPage={setPage}
                    />
                  )}
                </TabsContent>
              </Tabs>
              <p className="text-xs text-muted-foreground">仅展示当前工作区的 Skill</p>
            </div>
          )}
        </TabsContent>
      </Tabs>
      {importOpen && (
        <SkillImportDialog
          tenantId={tenantId}
          open={importOpen}
          onOpenChange={setImportOpen}
          onImported={setCreate}
          onWorkingChange={(working) => {
            setImporting(working)
            onDirtyChange(working)
          }}
        />
      )}
    </div>
  )
}
