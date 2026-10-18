import type * as React from 'react'
import { CircleAlert } from 'lucide-react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/utils'

const alertVariants = cva(
  'grid w-full grid-cols-[16px_1fr] items-start gap-x-2 rounded-md border px-3 py-2.5 text-sm',
  {
    variants: {
      variant: {
        default: 'border-border bg-surface text-foreground',
        destructive: 'border-danger/30 bg-danger-soft text-danger',
      },
    },
    defaultVariants: { variant: 'default' },
  },
)

function Alert({
  className,
  variant,
  children,
  ...props
}: React.ComponentProps<'div'> & VariantProps<typeof alertVariants>) {
  return (
    <div
      data-slot="alert"
      role="alert"
      className={cn(alertVariants({ variant }), className)}
      {...props}
    >
      {variant === 'destructive' && <CircleAlert className="mt-0.5 size-4" aria-hidden="true" />}
      {children}
    </div>
  )
}

function AlertTitle({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div data-slot="alert-title" className={cn('leading-5 font-medium', className)} {...props} />
  )
}

function AlertDescription({ className, ...props }: React.ComponentProps<'div'>) {
  return (
    <div
      data-slot="alert-description"
      className={cn('col-start-2 text-sm text-current/90', className)}
      {...props}
    />
  )
}

export { Alert, AlertDescription, AlertTitle, alertVariants }
