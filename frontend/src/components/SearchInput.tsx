import { Search, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import type { KeyboardEventHandler } from 'react'

// 列表搜索输入的统一形态：前缀图标加一键清空，宽度由调用方按布局给定。
export function SearchInput({
  value,
  onChange,
  label,
  placeholder,
  maxLength = 160,
  className,
  onKeyDown,
}: {
  value: string
  onChange: (value: string) => void
  label: string
  placeholder?: string
  maxLength?: number
  className?: string
  onKeyDown?: KeyboardEventHandler<HTMLInputElement>
}) {
  return (
    <div className={className ?? 'relative min-w-0 flex-1 md:w-72'}>
      <Search
        className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground"
        aria-hidden
      />
      <Input
        className="pr-9 pl-9"
        aria-label={label}
        placeholder={placeholder}
        maxLength={maxLength}
        value={value}
        onKeyDown={onKeyDown}
        onChange={(event) => onChange(event.target.value)}
      />
      {value && (
        <Button
          variant="ghost"
          size="icon-sm"
          className="absolute top-1/2 right-1 size-7 -translate-y-1/2"
          aria-label="清空搜索"
          onClick={() => onChange('')}
        >
          <X aria-hidden />
        </Button>
      )}
    </div>
  )
}
