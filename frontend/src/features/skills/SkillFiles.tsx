import { useState, type ChangeEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { cn } from '@/lib/utils'
import { skillApi, type SkillFile } from './api'

// 文件内容只显示为文本或文件信息；不以内联 HTML、脚本或 SVG 执行。
export function SkillFiles({
  tenantId,
  files,
  editable,
  busy,
  onChange,
  onWorkingChange,
}: {
  tenantId: number
  files: SkillFile[]
  editable: boolean
  busy: boolean
  onChange: (files: SkillFile[]) => void
  onWorkingChange: (working: boolean) => void
}) {
  const [selected, setSelected] = useState('SKILL.md')
  const [path, setPath] = useState('')
  const [error, setError] = useState('')
  const [uploading, setUploading] = useState(false)
  const file = files.find((f) => f.path === selected) ?? files[0]
  async function archive(e: ChangeEvent<HTMLInputElement>) {
    const zip = e.target.files?.[0]
    e.target.value = ''
    if (!zip) return
    setUploading(true)
    onWorkingChange(true)
    setError('')
    try {
      const content = await skillApi.preview(tenantId, zip)
      onChange(content.files)
      setSelected('SKILL.md')
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '压缩包读取失败')
    } finally {
      setUploading(false)
      onWorkingChange(false)
    }
  }
  async function replace(e: ChangeEvent<HTMLInputElement>) {
    const incoming = e.target.files?.[0]
    e.target.value = ''
    if (!incoming || !file) return
    if (incoming.size > 2 * 1024 * 1024) {
      setError('单文件不能超过 2 MiB')
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
      setError('')
    } catch {
      setError('文件读取失败')
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
      setError('请输入不重复的包内相对路径，不能包含 . 或 ..')
      return
    }
    onChange([...files, { path: name, text: '', base64: null, size: 0 }])
    setSelected(name)
    setPath('')
    setError('')
  }
  return (
    <div className="space-y-3">
      {editable && (
        <div className="flex flex-wrap items-center gap-3">
          <label className="text-sm">
            上传 ZIP 替换当前草稿
            <Input
              type="file"
              accept=".zip"
              aria-label="上传 ZIP 替换当前草稿"
              disabled={busy || uploading}
              onChange={(e) => void archive(e)}
            />
          </label>
          <p className="text-xs text-muted-foreground">
            最多 200 个文件、单文件 2 MiB、总内容 10 MiB。上传不修改平台基本信息。
          </p>
        </div>
      )}
      {uploading && <output>正在校验压缩包…</output>}
      {error && (
        <p className="text-sm text-danger" role="alert">
          {error}
        </p>
      )}
      <div className="grid min-w-0 rounded-lg border border-border bg-surface md:grid-cols-[220px_minmax(0,1fr)]">
        <div
          className="min-w-0 space-y-1 border-b border-border p-3 md:border-r md:border-b-0"
          aria-label="文件列表"
        >
          {files.map((f) => (
            <Button
              key={f.path}
              variant="ghost"
              className={cn(
                'h-auto w-full justify-start text-left break-all whitespace-normal',
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
              <Input
                aria-label="新文件相对路径"
                placeholder="references/guide.md"
                value={path}
                maxLength={240}
                disabled={busy || uploading || files.length >= 200}
                onChange={(e) => setPath(e.target.value)}
              />
              <Button
                variant="outline"
                size="sm"
                disabled={busy || uploading || files.length >= 200}
                onClick={add}
              >
                新增文件
              </Button>
            </div>
          )}
        </div>
        {file && (
          <div className="min-w-0">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border p-3 text-xs">
              <span className="break-all">{file.path}</span>
              <span className="text-muted-foreground">{editable ? '编辑中' : '只读'}</span>
            </div>
            {file.text !== null ? (
              <Textarea
                aria-label="文件内容"
                className="min-h-80 rounded-none border-0 font-mono"
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
            ) : (
              <p className="p-6 text-sm text-muted-foreground">二进制文件 · {file.size} 字节</p>
            )}
            {editable && (
              <div className="flex flex-wrap items-center gap-3 border-t border-border p-3">
                <label className="text-xs">
                  替换此文件
                  <Input
                    type="file"
                    aria-label="替换此文件"
                    disabled={busy || uploading}
                    onChange={(e) => void replace(e)}
                  />
                </label>
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
    </div>
  )
}
