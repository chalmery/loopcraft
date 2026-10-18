import { InfinityIcon } from 'lucide-react'
import { cn } from '@/lib/utils'

export function LoopCraftMark({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex size-10 shrink-0 items-center justify-center rounded-md bg-accent-soft text-accent',
        className,
      )}
    >
      <InfinityIcon className="size-6" aria-hidden="true" />
    </span>
  )
}
