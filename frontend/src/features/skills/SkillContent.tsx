import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { ErrorState } from '@/components/ErrorState'
import { errorMessage } from '@/lib/errors'
import { useSubmitting } from '@/lib/useSubmitting'
import { skillApi, type Content, type SkillFile } from './api'
import { SkillFiles } from './SkillFiles'

export function SkillContent({
  tenantId,
  skillId,
  number,
  latest,
  canManage,
  onSaved,
  onDirtyChange,
  onPendingChange,
}: {
  tenantId: number
  skillId: number
  number: number
  latest: number
  canManage: boolean
  onSaved: (number: number) => void
  onDirtyChange: (dirty: boolean) => void
  onPendingChange?: (pending: boolean) => void
}) {
  const submit = useSubmitting()
  const [content, setContent] = useState<Content | null>(null)
  const [draft, setDraft] = useState<SkillFile[] | null>(null)
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
        if (active) setError(errorMessage(cause, '内容加载失败'))
      },
    )
    return () => {
      active = false
    }
  }, [tenantId, skillId, number, reload])
  const dirty = draft !== null
  const busy = submit.busy
  useEffect(() => {
    onPendingChange?.(busy || working)
    return () => onPendingChange?.(false)
  }, [busy, working, onPendingChange])
  useEffect(() => {
    onDirtyChange(dirty)
    return () => onDirtyChange(false)
  }, [dirty, onDirtyChange])
  async function save() {
    if (!draft || working) return
    await submit.run(async () => {
      const saved = await skillApi.save(tenantId, skillId, number, requestId, draft)
      // 组件已按新版本重建时，旧保存结果不再落地。
      if (!submit.active.current) return
      setDraft(null)
      onSaved(saved.number)
      return `v${saved.number} 已保存`
    }, '版本保存失败')
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
                <Button loading={busy} disabled={busy || working} onClick={() => void save()}>
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
        <ErrorState message={error} onRetry={content ? undefined : () => setReload((n) => n + 1)} />
      )}
      {!content && !error && (
        <output className="text-sm text-muted-foreground">正在加载文件…</output>
      )}
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
