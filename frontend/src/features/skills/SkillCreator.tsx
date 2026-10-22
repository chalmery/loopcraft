import { useEffect, useState, type FormEvent } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { skillApi, type Skill, type SkillFile } from './api'
import { SkillFiles } from './SkillFiles'
import { TagEditor } from './TagEditor'

export function SkillCreator({
  tenantId,
  onClose,
  onSaved,
}: {
  tenantId: number
  onClose: () => void
  onSaved: (skill: Skill) => void
}) {
  const [name, setName] = useState(''),
    [slug, setSlug] = useState(''),
    [description, setDescription] = useState('')
  const [tags, setTags] = useState<string[]>([])
  const [files, setFiles] = useState<SkillFile[]>([
    { path: 'SKILL.md', size: 0, text: '# 工作步骤\n\n请填写 Skill 指令。\n', base64: null },
  ])
  const [requestId, setRequestId] = useState(() => crypto.randomUUID())
  const [busy, setBusy] = useState(false),
    [working, setWorking] = useState(false),
    [error, setError] = useState(''),
    [dirty, setDirty] = useState(false),
    [confirmClose, setConfirmClose] = useState(false)
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
  function changed() {
    setDirty(true)
    setRequestId(crypto.randomUUID())
  }
  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError('')
    try {
      const saved = await skillApi.create(
        tenantId,
        { slug, name, description, tags, requestId },
        files,
      )
      toast.success('Skill 与 v1 已创建')
      onSaved(saved)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '创建失败')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open && !busy && !working) {
          if (dirty) setConfirmClose(true)
          else onClose()
        }
      }}
    >
      <DialogContent className="max-w-5xl">
        <DialogTitle>新增 Skill</DialogTitle>
        <DialogDescription>
          填写平台基本信息，再在线编写或上传 ZIP。首次保存同时创建 Skill 和 v1。
        </DialogDescription>
        {confirmClose ? (
          <div className="space-y-4">
            <p>放弃尚未保存的 Skill 草稿？</p>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setConfirmClose(false)}>
                继续编辑
              </Button>
              <Button onClick={onClose}>放弃草稿</Button>
            </div>
          </div>
        ) : (
          <form onSubmit={(e) => void submit(e)} className="space-y-4">
            <div className="grid gap-4 sm:grid-cols-2">
              <Label className="grid gap-2">
                显示名称
                <Input
                  required
                  maxLength={80}
                  value={name}
                  disabled={busy}
                  onChange={(e) => {
                    setName(e.target.value)
                    changed()
                  }}
                />
              </Label>
              <Label className="grid gap-2">
                Skill 标识
                <Input
                  required
                  pattern="[a-z0-9]+(-[a-z0-9]+)*"
                  maxLength={64}
                  placeholder="例如 document-review"
                  value={slug}
                  disabled={busy}
                  onChange={(e) => {
                    setSlug(e.target.value)
                    changed()
                  }}
                />
              </Label>
              <Label htmlFor="skill-create-description" className="grid gap-2 sm:col-span-2">
                描述
                <Textarea
                  id="skill-create-description"
                  required
                  maxLength={1000}
                  rows={2}
                  value={description}
                  disabled={busy}
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
                  disabled={busy}
                  onChange={(next) => {
                    setTags(next)
                    changed()
                  }}
                />
              </div>
            </div>
            <SkillFiles
              tenantId={tenantId}
              files={files}
              editable
              busy={busy}
              onWorkingChange={setWorking}
              onChange={(next) => {
                setFiles(next)
                changed()
              }}
            />
            {error && (
              <p role="alert" className="text-sm text-danger">
                {error}
              </p>
            )}
            <div className="flex justify-end gap-2">
              <Button
                variant="outline"
                disabled={busy || working}
                onClick={() => {
                  if (dirty) setConfirmClose(true)
                  else onClose()
                }}
              >
                取消
              </Button>
              <Button type="submit" disabled={busy || working}>
                {busy ? '正在创建…' : '保存并创建 v1'}
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}
