import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ArrowLeft, Pencil } from 'lucide-react'
import { Form } from '@/components/ui/form'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
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
import { useNavigationGuard } from '@/components/useNavigationGuard'
import { toast } from 'sonner'
import { errorMessage } from '@/lib/errors'
import { useIsMounted } from '@/lib/useIsMounted'
import { usePagedList } from '@/lib/usePagedList'
import { useSubmitting } from '@/lib/useSubmitting'
import { skillApi, type Skill } from './api'
import { TagEditor } from './TagEditor'
import { SkillContent } from './SkillContent'

export function SkillDetail({
  tenantId,
  skill,
  canManage,
  onBack,
  onUpdated,
  onDirtyChange,
  onPendingChange,
}: {
  tenantId: number
  skill: Skill
  canManage: boolean
  onBack: () => void
  onUpdated: (s: Skill) => void
  onDirtyChange: (dirty: boolean) => void
  onPendingChange?: (pending: boolean) => void
}) {
  const editTrigger = useRef<HTMLButtonElement>(null)
  const nameInput = useRef<HTMLInputElement>(null)
  const focusRequested = useRef(false)
  const [editing, setEditing] = useState(false)
  useEffect(() => {
    if (!focusRequested.current) return
    focusRequested.current = false
    if (editing) nameInput.current?.focus()
    else editTrigger.current?.focus()
  }, [editing])
  const submit = useSubmitting()
  const mounted = useIsMounted()
  const metadataSequence = useRef(0)
  const [tab, setTab] = useState<'info' | 'content'>('info')
  const [number, setNumber] = useState(skill.latestVersion)
  const [name, setName] = useState(skill.name)
  const [description, setDescription] = useState(skill.description)
  const [tags, setTags] = useState(skill.tags)
  const [enabled, setEnabled] = useState(skill.enabled)
  const [metaDirty, setMetaDirty] = useState(false)
  const [contentDirty, setContentDirty] = useState(false)
  const [contentWorking, setContentWorking] = useState(false)
  const busy = submit.busy
  const history = usePagedList(
    (page: number) => skillApi.versions(tenantId, skill.id, page),
    [tenantId, skill.id, skill.latestVersion],
  )
  const dirty = metaDirty || contentDirty || busy || contentWorking
  useEffect(() => {
    onPendingChange?.(busy || contentWorking)
    return () => onPendingChange?.(false)
  }, [busy, contentWorking, onPendingChange])
  useEffect(() => {
    onDirtyChange(dirty)
    return () => onDirtyChange(false)
  }, [dirty, onDirtyChange])
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  const navigation = useNavigationGuard({
    locked: dirty,
    busy: busy || contentWorking,
    onDiscard: discard,
    title: '放弃尚未保存的修改？',
    description: '已保存的文件和历史版本不会改变。',
  })
  function discard() {
    setEditing(false)
    setName(skill.name)
    setDescription(skill.description)
    setTags(skill.tags)
    setEnabled(skill.enabled)
    setMetaDirty(false)
    setContentDirty(false)
  }
  function beginEditing() {
    discard()
    focusRequested.current = true
    setEditing(true)
  }
  function cancelEditing() {
    if (busy) return
    focusRequested.current = true
    discard()
  }
  async function save(e: FormEvent) {
    e.preventDefault()
    if (!editing || !canManage) return
    const metadataRequest = ++metadataSequence.current
    await submit.run(async () => {
      const saved = await skillApi.update(tenantId, skill.id, {
        name,
        description,
        tags,
        enabled,
        revision: skill.revision,
      })
      if (!mounted.current || metadataRequest !== metadataSequence.current) return
      setMetaDirty(false)
      onUpdated(saved)
      focusRequested.current = true
      setEditing(false)
      return '基本信息已保存'
    }, '保存失败')
  }
  return (
    <Tabs
      value={tab}
      onValueChange={(value) => {
        if ((value === 'info' || value === 'content') && value !== tab)
          navigation.guard(() => setTab(value))
      }}
      className="space-y-6"
    >
      <Button variant="ghost" size="sm" disabled={busy} onClick={() => navigation.guard(onBack)}>
        <ArrowLeft aria-hidden /> Skill 列表
      </Button>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold break-words">{skill.name}</h1>
          <p className="mt-2 font-mono text-xs break-all text-muted-foreground">{skill.slug}</p>
        </div>
        <div className="flex gap-2">
          <Badge variant="outline">{skill.origin === 'SYSTEM' ? '系统内置' : '租户自建'}</Badge>
          <Badge variant={skill.enabled ? 'success' : 'secondary'}>
            {skill.enabled ? '启用' : '禁用'}
          </Badge>
        </div>
      </div>
      {!canManage && (
        <p className="text-sm text-muted-foreground">
          系统内置 Skill 仅租户负责人和平台管理员可管理。
        </p>
      )}
      <TabsList aria-label="Skill 详情">
        <TabsTrigger value="info" disabled={busy}>
          基本信息
        </TabsTrigger>
        <TabsTrigger value="content" disabled={busy}>
          文件与版本
        </TabsTrigger>
      </TabsList>
      {tab === 'info' ? (
        <TabsContent
          value="info"
          className="grid grid-cols-1 items-start gap-6 xl:grid-cols-[minmax(0,1.45fr)_minmax(320px,1fr)]"
        >
          <Card>
            {editing && canManage ? (
              <Form onSubmit={(e) => void save(e)} className="space-y-5">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <div>
                    <CardTitle>编辑基本信息</CardTitle>
                    <p className="mt-1 text-xs text-muted-foreground">
                      管理 Skill 在租户中的展示信息。
                    </p>
                  </div>
                  {canManage && (
                    <div className="flex gap-2">
                      <Button variant="outline" disabled={busy} onClick={cancelEditing}>
                        取消
                      </Button>
                      <Button type="submit" disabled={busy || !metaDirty}>
                        {busy ? '正在保存…' : '保存基本信息'}
                      </Button>
                    </div>
                  )}
                </div>
                <div className="grid gap-5 sm:grid-cols-2">
                  <Label className="grid gap-2">
                    显示名称
                    <span className="text-xs font-normal text-muted-foreground">
                      在租户中展示的名称
                    </span>
                    <Input
                      ref={nameInput}
                      required
                      maxLength={80}
                      value={name}
                      disabled={!canManage || busy}
                      onChange={(e) => {
                        setName(e.target.value)
                        setMetaDirty(true)
                      }}
                    />
                  </Label>
                  <Label className="grid gap-2">
                    Skill 标识
                    <span className="text-xs font-normal text-muted-foreground">
                      创建后固定，用于唯一识别
                    </span>
                    <span className="py-2 font-mono text-sm break-all text-secondary">
                      {skill.slug}
                    </span>
                  </Label>
                  <Label className="grid gap-2">
                    来源
                    <span className="py-2 text-sm text-secondary">
                      {skill.origin === 'SYSTEM' ? '系统内置' : '租户自建'}
                    </span>
                  </Label>
                  <Label htmlFor="skill-status" className="grid gap-2">
                    状态
                    <Select
                      value={String(enabled)}
                      disabled={!canManage || busy}
                      onValueChange={(value) => {
                        setEnabled(value === 'true')
                        setMetaDirty(true)
                      }}
                    >
                      <SelectTrigger id="skill-status">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        <SelectItem value="true">启用</SelectItem>
                        <SelectItem value="false">禁用</SelectItem>
                      </SelectContent>
                    </Select>
                  </Label>
                  <Label htmlFor="skill-detail-description" className="grid gap-2 sm:col-span-2">
                    描述
                    <Textarea
                      id="skill-detail-description"
                      rows={4}
                      maxLength={1000}
                      required
                      value={description}
                      disabled={!canManage || busy}
                      onChange={(e) => {
                        setDescription(e.target.value)
                        setMetaDirty(true)
                      }}
                    />
                  </Label>
                  <div className="space-y-2 sm:col-span-2">
                    <span className="text-sm font-medium">标签</span>
                    <TagEditor
                      value={tags}
                      disabled={!canManage || busy}
                      onChange={(next) => {
                        setTags(next)
                        setMetaDirty(true)
                      }}
                    />
                  </div>
                </div>
                <p className="text-xs text-muted-foreground">
                  平台基本信息与包内文件独立，互不自动覆盖。禁用后不提供给模型。
                </p>
              </Form>
            ) : (
              <>
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div>
                    <CardTitle>基本信息</CardTitle>
                    <p className="mt-1 text-xs text-muted-foreground">Skill 在租户中的展示信息。</p>
                  </div>
                  {canManage && (
                    <div className="flex gap-2">
                      <DeleteRecord
                        name={skill.name}
                        description="将删除此 Skill 及全部版本，文件会在后台清理。"
                        onDelete={() => skillApi.delete(tenantId, skill)}
                        onDeleted={onBack}
                      />
                      <Button ref={editTrigger} variant="outline" onClick={beginEditing}>
                        <Pencil aria-hidden /> 编辑
                      </Button>
                    </div>
                  )}
                </div>
                <dl className="grid gap-x-6 gap-y-5 text-sm sm:grid-cols-2">
                  <div className="min-w-0 space-y-2">
                    <dt className="text-muted-foreground">显示名称</dt>
                    <dd className="font-medium break-words">{skill.name}</dd>
                  </div>
                  <div className="min-w-0 space-y-2">
                    <dt className="text-muted-foreground">Skill 标识</dt>
                    <dd className="font-mono break-all">{skill.slug}</dd>
                  </div>
                  <div className="space-y-2">
                    <dt className="text-muted-foreground">来源</dt>
                    <dd>{skill.origin === 'SYSTEM' ? '系统内置' : '租户自建'}</dd>
                  </div>
                  <div className="space-y-2">
                    <dt className="text-muted-foreground">状态</dt>
                    <dd>
                      <Badge variant={skill.enabled ? 'success' : 'secondary'}>
                        {skill.enabled ? '启用' : '禁用'}
                      </Badge>
                    </dd>
                  </div>
                  <div className="min-w-0 space-y-2 sm:col-span-2">
                    <dt className="text-muted-foreground">描述</dt>
                    <dd className="leading-6 break-words whitespace-pre-wrap text-secondary">
                      {skill.description}
                    </dd>
                  </div>
                  <div className="space-y-2 sm:col-span-2">
                    <dt className="text-muted-foreground">标签</dt>
                    <dd className="flex flex-wrap gap-2">
                      {skill.tags.length ? (
                        skill.tags.map((tag) => (
                          <Badge
                            key={tag}
                            variant="secondary"
                            className="max-w-full break-all whitespace-normal"
                          >
                            {tag}
                          </Badge>
                        ))
                      ) : (
                        <span className="text-muted-foreground">暂无标签</span>
                      )}
                    </dd>
                  </div>
                </dl>
              </>
            )}
          </Card>
          <Card>
            <CardTitle>
              内容版本{' '}
              <span className="text-xs font-normal text-muted-foreground">
                {history.data?.total ?? 0} 个版本
              </span>
            </CardTitle>
            <p className="text-xs text-muted-foreground">每次保存内容生成一个版本</p>
            {history.error && <ErrorState message={history.error} onRetry={history.reload} />}
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>版本</TableHead>
                  <TableHead>文件数</TableHead>
                  <TableHead>保存时间</TableHead>
                  <TableHead className="text-right">操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {history.data?.records.map((v) => (
                  <TableRow key={v.number}>
                    <TableCell>
                      v{v.number}{' '}
                      {v.number === skill.latestVersion && <Badge variant="outline">最新</Badge>}
                    </TableCell>
                    <TableCell>{v.fileCount}</TableCell>
                    <TableCell className="text-xs whitespace-nowrap">
                      {new Date(v.createdAt).toLocaleString()}
                    </TableCell>
                    <TableCell className="text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() =>
                          navigation.guard(() => {
                            setNumber(v.number)
                            setTab('content')
                          })
                        }
                      >
                        查看详情
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {!history.data && !history.error && (
              <output className="text-sm text-muted-foreground">正在加载版本…</output>
            )}
            {history.data && (
              <ListPagination
                page={history.page}
                size={history.data.size}
                total={history.data.total}
                pending={busy}
                onPage={history.setPage}
              />
            )}
          </Card>
        </TabsContent>
      ) : (
        <TabsContent value="content">
          <SkillContent
            key={`${skill.id}:${number}`}
            tenantId={tenantId}
            skillId={skill.id}
            number={number}
            latest={skill.latestVersion}
            canManage={canManage}
            onDirtyChange={setContentDirty}
            onPendingChange={setContentWorking}
            onSaved={(saved) => {
              setNumber(saved)
              const metadataRequest = ++metadataSequence.current
              void skillApi
                .detail(tenantId, skill.id)
                .then((updated) => {
                  if (mounted.current && metadataRequest === metadataSequence.current)
                    onUpdated(updated)
                })
                .catch((cause: unknown) => {
                  if (mounted.current && metadataRequest === metadataSequence.current)
                    toast.error(errorMessage(cause, '基本信息刷新失败'))
                })
            }}
          />
        </TabsContent>
      )}
      {navigation.dialog}
    </Tabs>
  )
}
