import { useRef, useState } from 'react'
import { Bot, Plus } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { ErrorState } from '@/components/ErrorState'
import { ListPagination } from '@/components/ListPagination'
import { PageHeader } from '@/components/PageHeader'
import { SearchInput } from '@/components/SearchInput'
import { usePagedList } from '@/lib/usePagedList'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { templateApi } from './api'
import { TemplateEditor } from './TemplateEditor'

export function TemplateManagement({
  tenantId,
  onDirtyChange,
  onPendingChange,
}: {
  tenantId: number
  onDirtyChange: (dirty: boolean) => void
  onPendingChange: (pending: boolean) => void
}) {
  const [query, setQuery] = useState('')
  const search = useDebouncedValue(query)
  const [filter, setFilter] = useState('all')
  const list = usePagedList(
    (page) => templateApi.list(tenantId, page, search, filter),
    [tenantId, search, filter],
  )
  const [editor, setEditor] = useState<{ key: number; id: number | null; copy: boolean } | null>(
    null,
  )
  const sequence = useRef(0)
  const counts = list.data?.counts ?? { all: 0, enabled: 0, disabled: 0 }
  function open(id: number | null, copy = false) {
    setEditor({ key: ++sequence.current, id, copy })
  }
  function back() {
    setEditor(null)
    onDirtyChange(false)
    list.reload()
  }
  if (editor)
    return (
      <TemplateEditor
        key={editor.key}
        tenantId={tenantId}
        id={editor.id}
        copy={editor.copy}
        onBack={back}
        onSaved={list.reload}
        onCopy={(id) => open(id, true)}
        onDirtyChange={onDirtyChange}
        onPendingChange={onPendingChange}
      />
    )
  const filtered = search !== '' || filter !== 'all'
  return (
    <div className="space-y-5">
      <PageHeader
        title="模板配置"
        description="把模型、提示词和 Skill 组合成可复用的 Agent。"
        badge={<Badge variant="secondary">{list.loading ? '—' : counts.all}</Badge>}
        actions={
          <Button onClick={() => open(null)}>
            <Plus aria-hidden />
            新建模板
          </Button>
        }
      />
      <Tabs
        value={filter}
        onValueChange={(value) => {
          setFilter(value)
          list.setPage(1)
        }}
      >
        <div className="flex flex-wrap items-end justify-between gap-3 border-b border-border">
          <TabsList aria-label="模板状态" className="border-0">
            <TabsTrigger value="all">
              全部 <span className="text-xs font-normal">{counts.all}</span>
            </TabsTrigger>
            <TabsTrigger value="enabled">
              已启用 <span className="text-xs font-normal">{counts.enabled}</span>
            </TabsTrigger>
            <TabsTrigger value="disabled">
              已停用 <span className="text-xs font-normal">{counts.disabled}</span>
            </TabsTrigger>
          </TabsList>
          <SearchInput
            className="relative mb-2 w-full md:w-72"
            label="搜索模板"
            placeholder="搜索名称或描述"
            value={query}
            onChange={(q) => {
              setQuery(q)
              list.setPage(1)
            }}
          />
        </div>
      </Tabs>
      <p className="text-xs text-muted-foreground" aria-live="polite">
        {list.loading ? '正在加载模板…' : `共 ${list.data?.total ?? 0} 个模板`}
      </p>
      {list.error && !list.loading ? (
        <ErrorState
          variant="block"
          title="模板加载失败"
          message={list.error}
          onRetry={list.reload}
        />
      ) : list.loading ? (
        <div
          aria-busy="true"
          aria-label="正在加载模板"
          className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3"
        >
          {[1, 2, 3].map((n) => (
            <Card key={n} className="min-h-52">
              <div className="h-4 w-1/2 rounded-md bg-surface-subtle" />
              <div className="mt-4 h-12 rounded-md bg-surface-subtle" />
            </Card>
          ))}
        </div>
      ) : list.data?.records.length ? (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {list.data.records.map((item) => (
            <Card
              key={item.id}
              className="relative min-w-0 transition-colors focus-within:border-border-strong hover:border-border-strong"
            >
              <Button
                variant="link"
                className="absolute inset-0 h-full w-full rounded-lg p-0"
                aria-label={`编辑 ${item.name}`}
                onClick={() => open(item.id)}
              >
                <span className="sr-only">编辑 {item.name}</span>
              </Button>
              <div className="flex min-w-0 items-center justify-between gap-3">
                <h2 className="truncate text-base font-semibold" title={item.name}>
                  {item.name}
                </h2>
                <Badge variant="secondary">{item.enabled ? '已启用' : '已停用'}</Badge>
              </div>
              <p className="line-clamp-2 min-h-12 text-sm leading-6 text-muted-foreground">
                {item.description || '暂无描述'}
              </p>
              <div className="flex flex-wrap items-center gap-2 text-xs text-secondary">
                <span>{item.modelName}</span>
                {!item.available && (
                  <Badge
                    className="border-transparent bg-warning-soft text-warning"
                    variant="outline"
                  >
                    配置不可用
                  </Badge>
                )}
              </div>
              <div className="mt-auto flex flex-wrap justify-between gap-2 border-t border-border pt-3 text-xs text-muted-foreground">
                <span>{item.skillCount} 个 Skill</span>
                <span>{new Date(item.updatedAt).toLocaleDateString('zh-CN')} 更新</span>
              </div>
            </Card>
          ))}
        </div>
      ) : (
        <div className="flex min-h-80 flex-col items-center justify-center gap-4 rounded-lg border border-dashed border-border p-6 text-center">
          <Bot aria-hidden className="size-6 text-muted-foreground" />
          <h2 className="text-xl font-semibold">
            {filtered ? '没有找到匹配的模板' : '创建你的第一个 Agent 模板'}
          </h2>
          <p className="text-sm text-muted-foreground">
            {filtered
              ? '试试其他关键词，或清空筛选条件。'
              : '选择运行模型，按需要编写提示词和添加 Skill。'}
          </p>
          <Button
            variant={filtered ? 'outline' : 'default'}
            onClick={() => {
              if (filtered) {
                setQuery('')
                setFilter('all')
                list.setPage(1)
              } else open(null)
            }}
          >
            {filtered ? '清空筛选' : '新建模板'}
          </Button>
        </div>
      )}
      {!list.loading && !list.error && (
        <ListPagination
          page={list.page}
          size={list.data?.size ?? 12}
          total={list.data?.total ?? 0}
          pending={list.loading}
          onPage={list.setPage}
        />
      )}
    </div>
  )
}
