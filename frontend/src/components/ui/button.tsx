import type * as React from 'react'
import { Loader2 } from 'lucide-react'
import { cva, type VariantProps } from 'class-variance-authority'
import { Slot } from 'radix-ui'
import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-md text-sm font-medium whitespace-nowrap focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:cursor-not-allowed disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        default: 'bg-primary text-primary-foreground hover:enabled:bg-accent-hover',
        destructive: 'bg-destructive text-primary-foreground hover:enabled:opacity-90',
        outline: 'border border-border bg-surface text-secondary hover:enabled:bg-surface-subtle',
        secondary: 'bg-surface-subtle text-secondary hover:enabled:bg-surface-hover',
        ghost: 'text-secondary hover:enabled:bg-surface-subtle',
        'danger-ghost': 'text-danger hover:enabled:bg-danger-soft',
        link: 'text-primary underline-offset-4 hover:underline',
      },
      size: {
        default: 'h-9 px-3 py-2',
        sm: 'min-h-8 px-2 py-1',
        lg: 'h-10 px-6 py-2',
        icon: 'size-9 p-2',
        'icon-sm': 'size-8 p-1',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  },
)

function Button({
  className,
  variant,
  size,
  asChild = false,
  type = 'button',
  loading = false,
  disabled,
  children,
  ...props
}: React.ComponentProps<'button'> &
  VariantProps<typeof buttonVariants> & { asChild?: boolean; loading?: boolean }) {
  const Comp = asChild ? Slot.Root : 'button'
  return (
    <Comp
      data-slot="button"
      type={type}
      disabled={disabled || loading}
      className={cn(buttonVariants({ variant, size }), className)}
      {...props}
    >
      {loading && <Loader2 className="animate-spin" aria-hidden="true" />}
      {children}
    </Comp>
  )
}

export { Button, buttonVariants }
