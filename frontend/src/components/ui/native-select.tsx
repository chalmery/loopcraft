import type * as React from 'react'
import { ChevronDownIcon } from 'lucide-react'
import { cn } from '@/lib/utils'

function NativeSelect({
  className,
  size = 'default',
  ...props
}: Omit<React.ComponentProps<'select'>, 'size'> & { size?: 'sm' | 'default' }) {
  return (
    <div
      data-slot="native-select-wrapper"
      className="relative min-w-0 has-[select:disabled]:opacity-50"
    >
      <select
        data-slot="native-select"
        data-size={size}
        className={cn(
          'h-9 w-full min-w-0 appearance-none rounded-md border border-input bg-surface py-2 pr-8 pl-3 text-base text-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-not-allowed aria-invalid:border-destructive data-[size=sm]:h-8 data-[size=sm]:py-1 md:text-sm',
          className,
        )}
        {...props}
      />
      <ChevronDownIcon
        className="pointer-events-none absolute top-1/2 right-2 size-4 -translate-y-1/2 text-muted-foreground"
        aria-hidden="true"
      />
    </div>
  )
}

function NativeSelectOption({ className, ...props }: React.ComponentProps<'option'>) {
  return (
    <option
      data-slot="native-select-option"
      className={cn('bg-surface text-foreground', className)}
      {...props}
    />
  )
}
function NativeSelectOptGroup({ className, ...props }: React.ComponentProps<'optgroup'>) {
  return (
    <optgroup
      data-slot="native-select-optgroup"
      className={cn('bg-surface text-foreground', className)}
      {...props}
    />
  )
}
export { NativeSelect, NativeSelectOption, NativeSelectOptGroup }
