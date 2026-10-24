import { useEffect, useRef, useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { ArrowLeft } from 'lucide-react'
import { Form } from '@/components/ui/form'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
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
import { skillApi, type PackagePreview, type Skill, type SkillFile } from './api'
import { SkillFiles } from './SkillFiles'
import { TagEditor } from './TagEditor'

type SkillCreatorProps = {
  tenantId: number
  imported: PackagePreview | null
  onClose: () => void
  onSaved: (skill: Skill) => void
  onDirtyChange: (dirty: boolean) => void
  onPendingChange?: (pending: boolean) => void
}

export function SkillCreator(props: SkillCreatorProps) {
  return <SkillCreatorState key={props.tenantId} {...props} />
}

function SkillCreatorState({
  tenantId,
  imported,
  onClose,
  onSaved,
  onDirtyChange,
  onPendingChange,
}: SkillCreatorProps) {
  const [name, setName] = useState(imported?.name ?? '')
  const [slug, setSlug] = useState(imported?.slug ?? '')
  const [description, setDescription] = useState(imported?.description ?? '')
  const [tab, setTab] = useState<'info' | 'content'>('info')
  const [tags, setTags] = useState<string[]>([])
  const [files, setFiles] = useState<SkillFile[]>(
    imported?.files ?? [
      { path: 'SKILL.md', size: 0, text: '# 工作步骤\n\n请填写 Skill 指令。\n', base64: null },
    ],
  )
  const [requestId, setRequestId] = useState(() => crypto.randomUUID())
  const [busy, setBusy] = useState(false)
  const [working, setWorking] = useState(false)
  const [dirty, setDirty] = useState(imported !== null)
  const [confirmClose, setConfirmClose] = useState(false)
  const submitting = useRef(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  useEffect(() => {
    onPendingChange?.(busy || working)
    return () => onPendingChange?.(false)
  }, [busy, working, onPendingChange])
  useEffect(() => {
    onDirtyChange(dirty || busy || working)
    return () => onDirtyChange(false)
  }, [dirty, busy, working, onDirtyChange])
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => {
      if (dirty || busy || working) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty, busy, working])
  function changed() {
    setDirty(true)
    setRequestId(crypto.randomUUID())
  }
  function close() {
    if (dirty) setConfirmClose(true)
    else onClose()
  }
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (submitting.current || working) return
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    try {
      const saved = await skillApi.create(
        tenantId,
        { slug, name, description, tags, requestId },
        files,
      )
      if (!mounted.current) return
      toast.success('Skill 与 v1 已创建')
      onSaved(saved)
    } catch (cause) {
      if (mounted.current) toast.error(cause instanceof Error ? cause.message : '创建失败')
    } finally {
      submitting.current = false
      if (mounted.current) setBusy(false)
    }
  }
  return (
    <Tabs
      value={tab}
      onValueChange={(value) => {
        if (value === 'info' || value === 'content') setTab(value)
      }}
      className="space-y-6"
    >
      <Button variant="ghost" size="sm" disabled={busy || working} onClick={close}>
        <ArrowLeft aria-hidden /> Skill 列表
      </Button>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{imported ? '编辑导入的 Skill' : '新增 Skill'}</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            {imported
              ? `已导入 ${files.length} 个文件，确认信息后创建。`
              : '填写基本信息，编写内容后创建。'}
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" disabled={busy || working} onClick={close}>
            取消
          </Button>
          <Button type="submit" form="skill-create-form" disabled={busy || working}>
            {busy ? '正在保存…' : '创建 Skill'}
          </Button>
        </div>
      </div>
      <TabsList aria-label="Skill 编辑区域">
        <TabsTrigger value="info" disabled={busy || working}>
          基本信息
        </TabsTrigger>
        <TabsTrigger value="content" disabled={busy || working}>
          文件与版本
        </TabsTrigger>
      </TabsList>
      <Form
        id="skill-create-form"
        onValidationError={() => setTab('info')}
        onSubmit={(e) => void submit(e)}
        className="space-y-4"
      >
        <TabsContent
          value="info"
          forceMount
          hidden={tab !== 'info'}
          aria-label="基本信息"
          className="max-w-3xl"
        >
          <Card className="gap-5">
            <div className="grid items-start gap-5 sm:grid-cols-2">
              <Label className="grid gap-2">
                显示名称
                <span className="text-xs font-normal text-muted-foreground">
                  在工作区中展示的名称
                </span>
                <Input
                  required
                  maxLength={80}
                  placeholder="例如 文档审阅"
                  value={name}
                  disabled={busy || working}
                  onChange={(e) => {
                    setName(e.target.value)
                    changed()
                  }}
                />
              </Label>
              <Label className="grid gap-2">
                Skill 标识
                <span className="text-xs font-normal text-muted-foreground">
                  创建后固定，用于唯一识别
                </span>
                <Input
                  required
                  pattern="[a-z0-9]+(-[a-z0-9]+)*"
                  maxLength={64}
                  placeholder="例如 document-review"
                  value={slug}
                  disabled={busy || working}
                  onChange={(e) => {
                    setSlug(e.target.value)
                    changed()
                  }}
                />
                <span className="text-xs font-normal text-muted-foreground">
                  小写字母、数字与短横线；创建后不可修改。
                </span>
              </Label>
              <Label htmlFor="skill-create-description" className="grid gap-2 sm:col-span-2">
                描述
                <Textarea
                  id="skill-create-description"
                  required
                  maxLength={1000}
                  rows={4}
                  placeholder="这个 Skill 用于什么场景，能完成什么任务？"
                  value={description}
                  disabled={busy || working}
                  onChange={(e) => {
                    setDescription(e.target.value)
                    changed()
                  }}
                />
              </Label>
              <div className="space-y-2 sm:col-span-2">
                <span className="text-sm font-medium">标签</span>
                <TagEditor
                  value={tags}
                  disabled={busy || working}
                  onChange={(next) => {
                    setTags(next)
                    changed()
                  }}
                />
              </div>
            </div>
          </Card>
        </TabsContent>
        <TabsContent value="content" forceMount hidden={tab !== 'content'} aria-label="文件与版本">
          <div className="space-y-3">
            <SkillFiles
              tenantId={tenantId}
              files={files}
              editable
              allowArchive={false}
              busy={busy}
              onWorkingChange={setWorking}
              onChange={(next) => {
                setFiles(next)
                changed()
              }}
            />
          </div>
        </TabsContent>
        <p className="text-xs text-muted-foreground">
          首次保存将创建 v1，基本信息与全部文件一并保存。
        </p>
      </Form>
      <AlertDialog open={confirmClose} onOpenChange={setConfirmClose}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>放弃尚未保存的 Skill 草稿？</AlertDialogTitle>
            <AlertDialogDescription>
              返回列表后，本次基本信息与文件修改将丢失。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>继续编辑</AlertDialogCancel>
            <AlertDialogAction onClick={onClose}>放弃草稿</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Tabs>
  )
}
