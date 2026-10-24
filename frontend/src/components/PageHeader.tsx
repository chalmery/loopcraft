import type { ReactNode } from 'react'

// 页面级标题区的统一形态：标题、描述、右侧动作，字号沿用项目尺度。
export function PageHeader({
  title,
  description,
  actions,
  badge,
}: {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  badge?: ReactNode
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-x-5 gap-y-3">
      <div className="min-w-0">
        <h1 className="flex flex-wrap items-center gap-3 text-2xl font-semibold">
          <span className="min-w-0 break-words">{title}</span>
          {badge}
        </h1>
        {description && <p className="mt-2 text-sm text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  )
}
