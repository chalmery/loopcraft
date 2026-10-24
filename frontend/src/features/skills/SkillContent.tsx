import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { skillApi, type Content, type SkillFile } from './api'
import { SkillFiles } from './SkillFiles'

type SkillContentProps = {
  tenantId: number
  skillId: number
  number: number
  latest: number
  canManage: boolean
  onSaved: (number: number) => void
  onDirtyChange: (dirty: boolean) => void
  onPendingChange?: (pending: boolean) => void
}

export function SkillContent(props: SkillContentProps) {
  return <SkillContentState key={`${props.tenantId}:${props.skillId}:${props.number}`} {...props} />
}

function SkillContentState({
  tenantId,
  skillId,
  number,
  latest,
  canManage,
  onSaved,
  onDirtyChange,
  onPendingChange,
}: SkillContentProps) {
  const submitting = useRef(false)
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const [content, setContent] = useState<Content | null>(null)
  const [draft, setDraft] = useState<SkillFile[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [working, setWorking] = useState(false)
  const [error, setError] = useState('')
  const [reload, setReload] = useState(0)
  const [requestId, setRequestId] = useState(() => crypto.randomUUID())
  useEffect(() => {
    let active = true
    void skillApi.content(tenantId, skillId, number).then(
      (value) => {
        if (active) {
          setContent(value)
          setError('')
        }
      },
      (cause: unknown) => {
        if (active) setError(cause instanceof Error ? cause.message : '内容加载失败')
      },
    )
    return () => {
      active = false
    }
  }, [tenantId, skillId, number, reload])
  const dirty = draft !== null
  useEffect(() => {
    onPendingChange?.(busy || working)
    return () => onPendingChange?.(false)
  }, [busy, working, onPendingChange])
  useEffect(() => {
    onDirtyChange(dirty)
    return () => onDirtyChange(false)
  }, [dirty, onDirtyChange])
  async function save() {
    if (!draft || submitting.current || working) return
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    setError('')
    try {
      const saved = await skillApi.save(tenantId, skillId, number, requestId, draft)
      if (!mounted.current) return
      setDraft(null)
      toast.success(`v${saved.number} 已保存`)
      onSaved(saved.number)
    } catch (cause) {
      if (mounted.current) toast.error(cause instanceof Error ? cause.message : '版本保存失败')
    } finally {
      submitting.current = false
      if (mounted.current) setBusy(false)
    }
  }
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-sm text-muted-foreground">
          v{number} · {number === latest ? '最新版本' : '历史版本，只能查看'}
        </span>
        {canManage && number === latest && content && (
          <div className="flex gap-2">
            {draft ? (
              <>
                <Button variant="outline" disabled={busy || working} onClick={() => setDraft(null)}>
                  放弃编辑
                </Button>
                <Button disabled={busy || working} onClick={() => void save()}>
                  {busy ? '正在保存…' : `保存为 v${latest + 1}`}
                </Button>
              </>
            ) : (
              <Button
                onClick={() => {
                  setDraft(structuredClone(content.files))
                  setRequestId(crypto.randomUUID())
                }}
              >
                编辑最新内容
              </Button>
            )}
          </div>
        )}
      </div>
      {error && (
        <div role="alert" className="flex items-center gap-3 text-sm text-danger">
          <span>{error}</span>
          {!content && (
            <Button variant="outline" onClick={() => setReload((n) => n + 1)}>
              重试
            </Button>
          )}
        </div>
      )}
      {!content && !error && <output>正在加载文件…</output>}
      {content && (
        <SkillFiles
          tenantId={tenantId}
          files={draft ?? content.files}
          editable={draft !== null && canManage}
          busy={busy}
          onWorkingChange={setWorking}
          onChange={(next) => {
            setDraft(next)
            setRequestId(crypto.randomUUID())
          }}
        />
      )}
    </div>
  )
}
