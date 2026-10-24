import { toast } from 'sonner'
import { useRef, useState, type ChangeEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'
import type { SkillFile } from './api'
import { SkillImportDialog } from './SkillImportDialog'

// 文件内容只显示为文本或文件信息；不以内联 HTML、脚本或 SVG 执行。
export function SkillFiles({
  tenantId,
  files,
  editable,
  allowArchive = true,
  busy,
  onChange,
  onWorkingChange,
}: {
  tenantId: number
  files: SkillFile[]
  editable: boolean
  allowArchive?: boolean
  busy: boolean
  onChange: (files: SkillFile[]) => void
  onWorkingChange: (working: boolean) => void
}) {
  const replacement = useRef<HTMLInputElement>(null)
  const gutter = useRef<HTMLPreElement>(null)
  const [archiveOpen, setArchiveOpen] = useState(false)
  const [adding, setAdding] = useState(false)
  const [selected, setSelected] = useState('SKILL.md')
  const [path, setPath] = useState('')
  const [uploading, setUploading] = useState(false)
  const file = files.find((f) => f.path === selected) ?? files[0]
  async function replace(e: ChangeEvent<HTMLInputElement>) {
    const incoming = e.target.files?.[0]
    e.target.value = ''
    if (!incoming || !file) return
    if (incoming.size > 2 * 1024 * 1024) {
      toast.error('单文件不能超过 2 MiB')
      return
    }
    setUploading(true)
    onWorkingChange(true)
    try {
      const bytes = new Uint8Array(await incoming.arrayBuffer())
      let text: string | null = null
      try {
        if (!bytes.includes(0))
          text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
      } catch {
        /* 二进制资源保留原字节。 */
      }
      let base64: string | null = null
      if (text === null) {
        let value = ''
        for (let i = 0; i < bytes.length; i += 8192)
          value += String.fromCharCode(...bytes.subarray(i, i + 8192))
        base64 = btoa(value)
      }
      onChange(
        files.map((f) => (f.path === file.path ? { ...f, size: bytes.length, text, base64 } : f)),
      )
    } catch {
      toast.error('文件读取失败')
    } finally {
      setUploading(false)
      onWorkingChange(false)
    }
  }
  function add() {
    const name = path.trim()
    if (
      !name ||
      name.startsWith('/') ||
      name.includes('\\') ||
      name.includes(':') ||
      name.split('/').some((p) => !p.trim() || p === '.' || p === '..') ||
      name.length > 240 ||
      files.some((f) => f.path === name)
    ) {
      toast.error('请输入不重复的包内相对路径，不能包含 . 或 ..')
      return
    }
    onChange([...files, { path: name, text: '', base64: null, size: 0 }])
    setSelected(name)
    setPath('')
    setAdding(false)
  }
  return (
    <div className="space-y-3">
      {editable && allowArchive && (
        <div className="flex flex-wrap items-center gap-3">
          <Button
            variant="outline"
            disabled={busy || uploading}
            onClick={() => setArchiveOpen(true)}
          >
            上传 ZIP 替换当前草稿
          </Button>
          <p className="text-xs text-muted-foreground">
            最多 200 个文件、单文件 2 MiB、总内容 10 MiB。上传不修改平台基本信息。
          </p>
        </div>
      )}
      {uploading && <output className="text-sm text-muted-foreground">正在处理文件…</output>}
      <div className="grid min-w-0 overflow-hidden rounded-lg border border-border bg-surface md:grid-cols-[220px_minmax(0,1fr)]">
        <div
          className="min-w-0 space-y-1 border-b border-border bg-canvas p-3 md:border-r md:border-b-0"
          aria-label="文件列表"
        >
          <div className="flex items-center justify-between px-2 pb-2 text-xs text-muted-foreground">
            <span>包内文件</span>
            <span>{files.length} 个</span>
          </div>
          {files.map((f) => (
            <Button
              key={f.path}
              variant="ghost"
              className={cn(
                'h-auto w-full justify-start text-left font-mono text-xs break-all whitespace-normal',
                file?.path === f.path && 'bg-accent-soft',
              )}
              title={f.path}
              onClick={() => setSelected(f.path)}
            >
              {f.path}
            </Button>
          ))}
          {editable && (
            <div className="space-y-2 border-t border-border pt-3">
              {adding && (
                <Input
                  aria-label="新文件相对路径"
                  placeholder="references/guide.md"
                  value={path}
                  maxLength={240}
                  disabled={busy || uploading || files.length >= 200}
                  onChange={(e) => setPath(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
                      e.preventDefault()
                      add()
                    }
                  }}
                />
              )}
              <Button
                variant="outline"
                size="sm"
                disabled={busy || uploading || files.length >= 200}
                onClick={() => {
                  if (adding) add()
                  else setAdding(true)
                }}
              >
                {adding ? '添加' : '新增文件'}
              </Button>
              {adding && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => {
                    setAdding(false)
                    setPath('')
                  }}
                >
                  取消
                </Button>
              )}
            </div>
          )}
        </div>
        {file && (
          <div className="min-w-0">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border bg-canvas px-4 py-3 text-xs">
              <span className="font-mono break-all">{file.path}</span>
              <span className="text-muted-foreground">{editable ? '编辑中' : '只读'}</span>
            </div>
            {file.text !== null ? (
              <div key={file.path} className="grid min-w-0 grid-cols-[2.5rem_minmax(0,1fr)]">
                <pre
                  ref={gutter}
                  aria-hidden="true"
                  className="m-0 overflow-hidden bg-canvas py-6 pr-2 text-right font-mono text-xs leading-6 text-border-strong select-none"
                >
                  {Array.from(
                    { length: file.text.split('\n').length },
                    (_, index) => index + 1,
                  ).join('\n')}
                </pre>
                <Textarea
                  aria-label="文件内容"
                  wrap="off"
                  spellCheck={false}
                  onScroll={(e) => {
                    if (gutter.current) gutter.current.scrollTop = e.currentTarget.scrollTop
                  }}
                  onKeyDown={(e) => {
                    // 编辑区内 Tab 插入缩进，而不是把焦点移出。
                    if (e.key === 'Tab' && !e.nativeEvent.isComposing && !e.shiftKey) {
                      e.preventDefault()
                      const target = e.currentTarget
                      const start = target.selectionStart
                      const end = target.selectionEnd
                      onChange(
                        files.map((f) =>
                          f.path === file.path
                            ? {
                                ...f,
                                text: `${f.text?.slice(0, start) ?? ''}  ${f.text?.slice(end) ?? ''}`,
                                size: new TextEncoder().encode(
                                  `${f.text?.slice(0, start) ?? ''}  ${f.text?.slice(end) ?? ''}`,
                                ).length,
                              }
                            : f,
                        ),
                      )
                      requestAnimationFrame(() => {
                        target.selectionStart = target.selectionEnd = start + 2
                      })
                    }
                  }}
                  className="min-h-96 resize-y rounded-none border-0 py-6 pr-4 font-mono text-xs leading-6"
                  value={file.text}
                  readOnly={!editable}
                  disabled={busy || uploading}
                  onChange={(e) =>
                    onChange(
                      files.map((f) =>
                        f.path === file.path
                          ? {
                              ...f,
                              text: e.target.value,
                              size: new TextEncoder().encode(e.target.value).length,
                            }
                          : f,
                      ),
                    )
                  }
                />
              </div>
            ) : (
              <p className="p-6 text-sm text-muted-foreground">二进制文件 · {file.size} 字节</p>
            )}
            {editable && (
              <div className="flex flex-wrap items-center gap-3 border-t border-border p-3">
                <Input
                  ref={replacement}
                  className="hidden"
                  type="file"
                  aria-label="替换此文件"
                  disabled={busy || uploading}
                  onChange={(e) => void replace(e)}
                />
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy || uploading}
                  onClick={() => replacement.current?.click()}
                >
                  替换文件
                </Button>
                <span className="text-xs text-muted-foreground">单文件最大 2 MiB</span>
                {file.path !== 'SKILL.md' && (
                  <Button
                    variant="ghost"
                    disabled={busy || uploading}
                    onClick={() => {
                      onChange(files.filter((f) => f.path !== file.path))
                      setSelected('SKILL.md')
                    }}
                  >
                    移除文件
                  </Button>
                )}
              </div>
            )}
          </div>
        )}
      </div>
      {archiveOpen && (
        <SkillImportDialog
          tenantId={tenantId}
          open={archiveOpen}
          onOpenChange={setArchiveOpen}
          onImported={(preview) => {
            onChange(preview.files)
            setSelected('SKILL.md')
          }}
          onWorkingChange={(working) => {
            setUploading(working)
            onWorkingChange(working)
          }}
        />
      )}
    </div>
  )
}
