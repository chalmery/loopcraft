import { toast } from 'sonner'
import { useEffect, useRef, useState } from 'react'
import { Upload } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog'
import { skillApi, type PackagePreview } from './api'

type SkillImportDialogProps = {
  tenantId: number
  open: boolean
  onOpenChange: (open: boolean) => void
  onImported: (preview: PackagePreview) => void
  onWorkingChange: (working: boolean) => void
}

export function SkillImportDialog(props: SkillImportDialogProps) {
  return <SkillImportDialogState key={props.tenantId} {...props} />
}

function SkillImportDialogState({
  tenantId,
  open,
  onOpenChange,
  onImported,
  onWorkingChange,
}: SkillImportDialogProps) {
  const active = useRef(true)
  const submitting = useRef(false)
  useEffect(() => {
    active.current = true
    return () => {
      active.current = false
    }
  }, [])
  const input = useRef<HTMLInputElement>(null)
  const [archive, setArchive] = useState<File | null>(null)
  const [busy, setBusy] = useState(false)
  async function submit() {
    if (!archive || submitting.current) return
    submitting.current = true
    setBusy(true)

    onWorkingChange(true)
    try {
      const preview = await skillApi.preview(tenantId, archive)
      if (!active.current) return
      onImported(preview)
      onOpenChange(false)
    } catch (cause) {
      if (active.current) toast.error(cause instanceof Error ? cause.message : '压缩包读取失败')
    } finally {
      submitting.current = false
      if (active.current) setBusy(false)
      onWorkingChange(false)
    }
  }
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!busy) onOpenChange(next)
      }}
    >
      <DialogContent
        className="max-w-lg"
        onEscapeKeyDown={(e) => {
          if (busy) e.preventDefault()
        }}
        onPointerDownOutside={(e) => {
          if (busy) e.preventDefault()
        }}
      >
        <DialogTitle className="text-base font-semibold">导入 Skill</DialogTitle>
        <DialogDescription className="text-sm text-muted-foreground">
          解析压缩包后，确认基本信息与文件内容再创建。
        </DialogDescription>
        <div className="flex min-w-0 flex-col items-center rounded-lg border border-dashed border-border bg-canvas px-6 py-8 text-center">
          <span className="mb-3 rounded-md border border-border bg-surface p-2">
            <Upload className="size-5 text-secondary" aria-hidden />
          </span>
          <h2 className="text-base font-medium">上传 Skill 压缩包</h2>
          <p className="mt-2 mb-5 text-xs text-muted-foreground">
            ZIP 格式，最大 20 MiB，包含 SKILL.md 与配套文件。
          </p>
          <Input
            ref={input}
            type="file"
            accept=".zip"
            aria-label="Skill ZIP 文件"
            className="hidden"
            disabled={busy}
            onChange={(e) => {
              const file = e.target.files?.[0]
              e.target.value = ''
              if (!file) return
              if (!file.name.toLowerCase().endsWith('.zip') || file.size > 20 * 1024 * 1024) {
                setArchive(null)
                toast.error(
                  file.size > 20 * 1024 * 1024 ? '压缩包不能超过 20 MiB' : '请选择 ZIP 格式的文件',
                )
                return
              }
              setArchive(file)
            }}
          />
          <Button variant="outline" disabled={busy} onClick={() => input.current?.click()}>
            {archive ? '重新选择文件' : '选择 ZIP 文件'}
          </Button>
          {archive && (
            <p className="mt-3 max-w-full font-mono text-xs break-all text-secondary">
              {archive.name}
            </p>
          )}
        </div>
        <div className="flex justify-end gap-2">
          <Button variant="outline" disabled={busy} onClick={() => onOpenChange(false)}>
            取消
          </Button>
          <Button disabled={!archive || busy} onClick={() => void submit()}>
            {busy ? '正在解析…' : '导入并继续'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
