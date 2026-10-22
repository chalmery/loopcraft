import { useEffect, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { ListPagination } from '@/features/admin/AdminListParts'
import { skillApi, type Page, type Skill } from './api'
import { SkillCreator } from './SkillCreator'
import { SkillDetail } from './SkillDetail'

export function SkillManagement({
  tenantId,
  canManageBuiltIn,
  onDirtyChange,
}: {
  tenantId: number
  canManageBuiltIn: boolean
  onDirtyChange: (dirty: boolean) => void
}) {
  const [result, setResult] = useState<Page<Skill>>({ records: [], total: 0, page: 1, size: 12 })
  const [page, setPage] = useState(1),
    [query, setQuery] = useState(''),
    [revision, setRevision] = useState(0),
    [finished, setFinished] = useState(''),
    [error, setError] = useState('')
  const [selected, setSelected] = useState<Skill | null>(null),
    [create, setCreate] = useState(false)
  const key = JSON.stringify([tenantId, page, query, revision]),
    loading = key !== finished
  useEffect(() => {
    let active = true
    void skillApi
      .list(tenantId, page, query)
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
  }, [tenantId, page, query, revision, key])
  function back() {
    setSelected(null)
    onDirtyChange(false)
    setRevision((n) => n + 1)
  }
  return selected ? (
    <SkillDetail
      key={`${selected.id}:${selected.revision}`}
      tenantId={tenantId}
      skill={selected}
      canManage={selected.origin === 'CUSTOM' || canManageBuiltIn}
      onBack={back}
      onUpdated={(saved) => {
        setSelected(saved)
        setRevision((n) => n + 1)
      }}
      onDirtyChange={onDirtyChange}
    />
  ) : (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">Skill 管理</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            管理当前工作区的 Skill 基本信息与内容版本。
          </p>
        </div>
        <Button data-admin-add onClick={() => setCreate(true)}>
          新增 Skill
        </Button>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Input
          className="sm:max-w-sm"
          aria-label="搜索 Skill"
          placeholder="搜索名称、标识、描述或标签"
          maxLength={160}
          value={query}
          onChange={(e) => {
            setQuery(e.target.value)
            setPage(1)
          }}
        />
        <Button variant="outline" disabled={loading} onClick={() => setRevision((n) => n + 1)}>
          刷新
        </Button>
        <span className="text-xs text-muted-foreground">{result.total} 个 Skill</span>
      </div>
      {loading && <output>正在加载 Skill…</output>}
      {error && !loading && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3" aria-busy={loading}>
        {!loading &&
          !error &&
          result.records.map((skill) => (
            <Card key={skill.id} density="compact">
              <div className="flex min-w-0 items-start justify-between gap-2">
                <div className="min-w-0">
                  <CardTitle className="truncate" title={skill.name}>
                    {skill.name}
                  </CardTitle>
                  <p
                    className="mt-1 truncate font-mono text-xs text-muted-foreground"
                    title={skill.slug}
                  >
                    {skill.slug}
                  </p>
                </div>
                <Badge variant="outline">v{skill.latestVersion}</Badge>
              </div>
              <p className="line-clamp-2 text-sm text-muted-foreground">{skill.description}</p>
              <div className="flex flex-wrap gap-1">
                {skill.origin === 'SYSTEM' && <Badge variant="outline">系统内置</Badge>}
                {!skill.enabled && <Badge variant="secondary">已禁用</Badge>}
                {skill.tags.map((tag) => (
                  <Badge key={tag} variant="secondary">
                    {tag}
                  </Badge>
                ))}
              </div>
              <div className="mt-auto flex items-center justify-between gap-2 border-t border-border pt-3">
                <span className="text-xs text-muted-foreground">{skill.latestVersion} 个版本</span>
                <Button variant="outline" size="sm" onClick={() => setSelected(skill)}>
                  查看详情
                </Button>
              </div>
            </Card>
          ))}
      </div>
      {!loading && !error && !result.records.length && (
        <p className="rounded-lg border border-dashed border-border p-8 text-center text-sm text-muted-foreground">
          {query ? '没有匹配的 Skill' : '还没有 Skill，点击新增开始编写或上传压缩包。'}
        </p>
      )}
      <ListPagination
        page={page}
        size={12}
        total={result.total}
        pending={loading}
        onPage={setPage}
      />
      {create && (
        <SkillCreator
          tenantId={tenantId}
          onClose={() => setCreate(false)}
          onSaved={(saved) => {
            setCreate(false)
            setSelected(saved)
            setRevision((n) => n + 1)
          }}
        />
      )}
    </div>
  )
}
