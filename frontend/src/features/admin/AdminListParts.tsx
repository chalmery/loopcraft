import { Plus, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'

export function ListHeading({
  title,
  description,
  pending,
  onAdd,
  onReload,
}: {
  title: string
  description: string
  pending: boolean
  onAdd: () => void
  onReload: () => void
}) {
  return (
    <>
      <div className="mb-6">
        <h1 className="text-xl font-semibold">{title}</h1>
        <p className="mt-2 text-sm text-muted-foreground">{description}</p>
      </div>
      <div className="mb-4 flex items-center justify-between gap-3">
        <Button data-admin-add onClick={onAdd}>
          <Plus aria-hidden="true" />
          新增
        </Button>
        <Button variant="outline" disabled={pending} onClick={onReload}>
          <RefreshCw aria-hidden="true" />
          刷新
        </Button>
      </div>
    </>
  )
}
