import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

// 页面内错误反馈的统一形态：inline 用于列表与局部区域，block 用于整块内容加载失败。
// 重试按钮保持同一变体，不再逐页发明新的错误视觉。
export function ErrorState({
  message,
  onRetry,
  variant = 'inline',
  title,
  retryText = '重试',
  className,
}: {
  message: string
  onRetry?: () => void
  variant?: 'inline' | 'block'
  title?: string
  retryText?: string
  className?: string
}) {
  if (variant === 'block')
    return (
      <div
        role="alert"
        className={cn('rounded-lg border border-border bg-canvas p-8 text-center', className)}
      >
        <h2 className="text-base font-medium">{title ?? '暂时无法加载'}</h2>
        <p className="my-3 text-sm text-danger">{message}</p>
        {onRetry && (
          <Button variant="outline" onClick={onRetry}>
            {retryText}
          </Button>
        )}
      </div>
    )
  return (
    <Alert variant="destructive" className={cn('items-center', className)}>
      <div className="col-start-2 flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1">
        <span className="min-w-0 break-words">
          {title && <span className="mr-1 font-medium">{title}</span>}
          {message}
        </span>
        {onRetry && (
          <Button variant="outline" size="sm" onClick={onRetry}>
            {retryText}
          </Button>
        )}
      </div>
    </Alert>
  )
}
