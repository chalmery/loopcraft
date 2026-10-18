import type * as React from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { Slot } from 'radix-ui'
import { cn } from '@/lib/utils'

const badgeVariants = cva(
  'inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-sm border border-transparent px-2 py-1 text-xs font-medium whitespace-nowrap [&>svg]:size-3',
  {
    variants: {
      variant: {
        default: 'bg-accent-soft text-accent',
        secondary: 'bg-surface-subtle text-muted-foreground',
        success: 'bg-success-soft text-success',
        destructive: 'bg-danger-soft text-danger',
        outline: 'border-border text-secondary',
      },
    },
    defaultVariants: { variant: 'default' },
  },
)
function Badge({
  className,
  variant,
  asChild = false,
  ...props
}: React.ComponentProps<'span'> & VariantProps<typeof badgeVariants> & { asChild?: boolean }) {
  const Comp = asChild ? Slot.Root : 'span'
  return <Comp data-slot="badge" className={cn(badgeVariants({ variant }), className)} {...props} />
}
export { Badge, badgeVariants }
