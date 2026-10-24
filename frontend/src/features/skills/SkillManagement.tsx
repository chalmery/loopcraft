import { useEffect, useState } from 'react'
import { FileText, LayoutGrid, List, Plus, Upload } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { ListPagination } from '@/components/ListPagination'
import { ErrorState } from '@/components/ErrorState'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { cn } from '@/lib/utils'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { usePagedList } from '@/lib/usePagedList'
import { skillApi, type PackagePreview, type SkillOrigin, type Skill } from './api'
import { SkillCreator } from './SkillCreator'
import { SkillDetail } from './SkillDetail'
import { SkillImportDialog } from './SkillImportDialog'

export function SkillManagement({
  tenantId,
  canManageBuiltIn,
  onDirtyChange,
  onPendingChange,
}: {
  tenantId: number
  canManageBuiltIn: boolean
  onDirtyChange: (dirty: boolean) => void
  onPendingChange?: (pending: boolean) => void
}) {
  const [query, setQuery] = useState('')
  const debouncedQuery = useDebouncedValue(query)
  const [origin, setOrigin] = useState<SkillOrigin>('all')
  const list = usePagedList(
    (page: number) => skillApi.list(tenantId, page, debouncedQuery, origin),
    [tenantId, debouncedQuery, origin],
  )
  const [selected, setSelected] = useState<Skill | null>(null)
  const [create, setCreate] = useState<PackagePreview | 'empty' | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const [importing, setImporting] = useState(false)
  const [view, setView] = useState<'grid' | 'list'>('grid')
  useEffect(() => {
    onPendingChange?.(importing)
    return () => onPendingChange?.(false)
  }, [importing, onPendingChange])
  function back() {
    setSelected(null)
    setCreate(null)
    onDirtyChange(false)
    list.reload()
  }
  function clearFilters() {
    setQuery('')
    setOrigin('all')
    list.setPage(1)
  }
  const counts = list.data?.counts ?? { custom: 0, system: 0 }
  const total = counts.custom + counts.system
  const filtered = !!debouncedQuery || origin !== 'all'
  const actions = (
    <>
      <Button variant="outline" disabled={importing} onClick={() => setImportOpen(true)}>
        <Upload aria-hidden />
        导入 Skill
      </Button>
      <Button disabled={importing} onClick={() => setCreate('empty')}>
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
              key={tenantId}
              tenantId={tenantId}
              imported={create === 'empty' ? null : create}
              onClose={back}
              onDirtyChange={onDirtyChange}
              onPendingChange={onPendingChange}
              onSaved={(saved) => {
                setCreate(null)
                setSelected(saved)
                onDirtyChange(false)
                list.reload()
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
                list.reload()
              }}
              onDirtyChange={onDirtyChange}
              onPendingChange={onPendingChange}
            />
          ) : (
            <div className="space-y-5">
              <PageHeader
                title="Skill 管理"
                description="沉淀可复用的工作方法，让每一次任务都有章可循。"
                actions={<div className="flex gap-2">{actions}</div>}
                badge={
                  <Badge variant="secondary">{list.loading && !list.data ? '—' : total}</Badge>
                }
              />
              <Tabs
                value={origin}
                onValueChange={(value) => {
                  if (value === 'all' || value === 'CUSTOM' || value === 'SYSTEM') {
                    setOrigin(value)
                    list.setPage(1)
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
                        {counts.custom}
                      </span>
                    </TabsTrigger>
                    <TabsTrigger value="SYSTEM">
                      内置
                      <span className="text-xs font-normal text-muted-foreground">
                        {counts.system}
                      </span>
                    </TabsTrigger>
                  </TabsList>
                  <div className="order-1 flex w-full items-center gap-2 pb-3 md:order-2 md:w-auto">
                    <SearchInput
                      value={query}
                      onChange={(value) => {
                        setQuery(value)
                        list.setPage(1)
                      }}
                      label="搜索 Skill"
                      placeholder="搜索名称、标识、描述或标签"
                    />
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
                      {list.loading
                        ? '正在加载…'
                        : filtered
                          ? `找到 ${list.data?.total ?? 0} 个 Skill`
                          : `共 ${list.data?.total ?? 0} 个 Skill`}
                    </span>
                    <span className="hidden sm:inline">名称、描述与内容版本独立管理</span>
                  </div>
                  {list.error && !list.loading ? (
                    <ErrorState
                      variant="block"
                      title="暂时无法加载 Skill"
                      message={list.error}
                      retryText="重新加载"
                      onRetry={list.reload}
                    />
                  ) : list.loading ? (
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
                  ) : list.data?.records.length ? (
                    <div
                      className={cn(
                        view === 'grid'
                          ? 'grid gap-4 sm:grid-cols-2 xl:grid-cols-3'
                          : 'divide-y divide-border overflow-hidden rounded-lg border border-border',
                      )}
                    >
                      {list.data.records.map((skill) => (
                        <Card
                          key={skill.id}
                          density="compact"
                          className={cn(
                            'relative gap-3 transition-colors focus-within:border-border-strong focus-within:bg-surface-hover hover:border-border-strong hover:bg-surface-hover',
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
                              {skill.origin === 'SYSTEM' ? '系统内置' : '租户自建'}
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
                        <Button variant="outline" className="mt-6" onClick={clearFilters}>
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
                  {!list.loading && !list.error && (list.data?.total ?? 0) > 0 && (
                    <ListPagination
                      page={list.page}
                      size={list.data?.size ?? 12}
                      total={list.data?.total ?? 0}
                      pending={list.loading}
                      onPage={list.setPage}
                    />
                  )}
                </TabsContent>
              </Tabs>
              <p className="text-xs text-muted-foreground">仅展示当前租户的 Skill</p>
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
