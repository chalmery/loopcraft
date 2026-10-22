import { useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'

export function TagEditor({
  value,
  onChange,
  disabled = false,
}: {
  value: string[]
  onChange: (tags: string[]) => void
  disabled?: boolean
}) {
  const [text, setText] = useState('')
  function add() {
    const tag = text.trim()
    if (!tag || disabled) return
    if (!value.includes(tag)) onChange([...value, tag])
    setText('')
  }
  return (
    <fieldset
      className="flex flex-wrap items-center gap-2 rounded-md border border-input bg-surface p-2"
      aria-label="标签"
    >
      {value.map((tag, index) => (
        <Badge key={tag} variant="secondary">
          {tag}
          {!disabled && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              aria-label={`移除标签 ${tag}`}
              onClick={() => onChange(value.filter((_, i) => i !== index))}
            >
              ×
            </Button>
          )}
        </Badge>
      ))}
      {!disabled && (
        <>
          <Input
            className="min-w-28 flex-1"
            aria-label="输入新标签"
            placeholder="输入后按 Enter"
            value={text}
            maxLength={32}
            disabled={value.length >= 20}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.nativeEvent.isComposing) {
                e.preventDefault()
                add()
              }
            }}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!text.trim() || value.length >= 20}
            onClick={add}
          >
            添加
          </Button>
        </>
      )}
    </fieldset>
  )
}
