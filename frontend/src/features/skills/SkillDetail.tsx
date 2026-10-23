import { useEffect, useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { ArrowLeft } from 'lucide-react'
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
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { DeleteRecord, ListPagination } from '@/features/admin/AdminListParts'
import { skillApi, type Page, type Skill, type Version } from './api'
import { TagEditor } from './TagEditor'
import { SkillContent } from './SkillContent'

export function SkillDetail({
  tenantId,
  skill,
  canManage,
  onBack,
  onUpdated,
  onDirtyChange,
}: {
  tenantId: number
  skill: Skill
  canManage: boolean
  onBack: () => void
  onUpdated: (s: Skill) => void
  onDirtyChange: (dirty: boolean) => void
}) {
  const [tab, setTab] = useState<'info' | 'content'>('info'),
    [number, setNumber] = useState(skill.latestVersion)
  const [name, setName] = useState(skill.name),
    [description, setDescription] = useState(skill.description),
    [tags, setTags] = useState(skill.tags),
    [enabled, setEnabled] = useState(skill.enabled)
  const [metaDirty, setMetaDirty] = useState(false),
    [contentDirty, setContentDirty] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState('')
  const [page, setPage] = useState(1),
    [history, setHistory] = useState<Page<Version> | null>(null),
    [historyError, setHistoryError] = useState(''),
    [reload, setReload] = useState(0)
  const [pending, setPending] = useState<(() => void) | null>(null)
  const dirty = metaDirty || contentDirty
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
  useEffect(() => {
    let active = true
    void skillApi.versions(tenantId, skill.id, page).then(
      (value) => {
        if (active) {
          setHistory(value)
          setHistoryError('')
        }
      },
      (cause: unknown) => {
        if (active) setHistoryError(cause instanceof Error ? cause.message : '版本加载失败')
      },
    )
    return () => {
      active = false
    }
  }, [tenantId, skill.id, skill.latestVersion, page, reload])
  function navigate(action: () => void) {
    if (dirty) setPending(() => action)
    else action()
  }
  function discard() {
    setName(skill.name)
    setDescription(skill.description)
    setTags(skill.tags)
    setEnabled(skill.enabled)
    setMetaDirty(false)
    setContentDirty(false)
  }
  async function save(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const saved = await skillApi.update(tenantId, skill.id, {
        name,
        description,
        tags,
        enabled,
        revision: skill.revision,
      })
      setMetaDirty(false)
      onUpdated(saved)
      toast.success('基本信息已保存')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '保存失败')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Tabs
      value={tab}
      onValueChange={(value) => {
        if ((value === 'info' || value === 'content') && value !== tab)
          navigate(() => setTab(value))
      }}
      className="space-y-6"
    >
      <Button variant="ghost" size="sm" disabled={busy} onClick={() => navigate(onBack)}>
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
          className="grid items-start gap-6 xl:grid-cols-[minmax(0,1.45fr)_minmax(320px,1fr)]"
        >
          <Card>
            <form onSubmit={(e) => void save(e)} className="space-y-5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <CardTitle>基本信息</CardTitle>
                  <p className="mt-1 text-xs text-muted-foreground">
                    管理 Skill 在工作区中的展示信息。
                  </p>
                </div>
                {canManage && (
                  <div className="flex gap-2">
                    <DeleteRecord
                      name={skill.name}
                      description="将删除此 Skill 及全部版本，文件会在后台清理。"
                      disabled={busy}
                      onDelete={() => skillApi.delete(tenantId, skill)}
                      onDeleted={onBack}
                    />
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
                    在工作区中展示的名称
                  </span>
                  <Input
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
                  <Input value={skill.slug} readOnly />
                </Label>
                <Label className="grid gap-2">
                  来源
                  <Input value={skill.origin === 'SYSTEM' ? '系统内置' : '租户自建'} readOnly />
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
              {error && (
                <p role="alert" className="text-sm text-danger">
                  {error}
                </p>
              )}
            </form>
          </Card>
          <Card>
            <CardTitle>
              内容版本{' '}
              <span className="text-xs font-normal text-muted-foreground">
                {history?.total ?? 0} 个版本
              </span>
            </CardTitle>
            <p className="text-xs text-muted-foreground">每次保存内容生成一个版本</p>
            {historyError && (
              <p role="alert" className="text-sm text-danger">
                {historyError}{' '}
                <Button variant="ghost" size="sm" onClick={() => setReload((n) => n + 1)}>
                  重试
                </Button>
              </p>
            )}
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
                {history?.records.map((v) => (
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
                          navigate(() => {
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
            {!history && !historyError && <output>正在加载版本…</output>}
            {history && (
              <ListPagination
                page={page}
                size={20}
                total={history.total}
                pending={busy}
                onPage={setPage}
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
            onSaved={(saved) => {
              setNumber(saved)
              void skillApi
                .detail(tenantId, skill.id)
                .then(onUpdated)
                .catch((cause: unknown) =>
                  setError(cause instanceof Error ? cause.message : '基本信息刷新失败'),
                )
            }}
          />
        </TabsContent>
      )}
      <AlertDialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (!open) setPending(null)
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>放弃尚未保存的修改？</AlertDialogTitle>
            <AlertDialogDescription>已保存的文件和历史版本不会改变。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>继续编辑</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                const action = pending
                setPending(null)
                discard()
                action?.()
              }}
            >
              放弃并继续
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Tabs>
  )
}
