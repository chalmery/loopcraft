import { ArrowRight, Building2 } from 'lucide-react'
import { Card } from '@/components/ui/card'
import type { Tenant } from './api'

export function TenantSelection({
  tenants,
  onSelect,
}: {
  tenants: Tenant[]
  onSelect: (tenant: Tenant) => void
}) {
  return (
    <section
      className="flex h-[calc(100dvh-56px)] items-start justify-center px-4 py-6 sm:py-12 lg:py-20"
      aria-labelledby="tenant-selection-title"
    >
      <Card className="h-[min(480px,calc(100vw-32px))] max-h-full w-full max-w-[480px] shrink-0 gap-0 overflow-hidden p-0 md:p-0">
        <div className="shrink-0 border-b border-border px-4 py-3">
          <h1 id="tenant-selection-title" className="text-base font-semibold">
            选择租户
          </h1>
        </div>
        {tenants.length ? (
          <ul className="flex min-h-0 flex-1 flex-col divide-y divide-border overflow-y-auto overscroll-contain">
            {tenants.map((tenant) => (
              <li key={tenant.id} className="shrink-0">
                <button
                  type="button"
                  className="flex min-h-12 w-full items-center gap-3 px-4 py-2 text-left transition-colors hover:bg-surface-subtle focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-ring"
                  onClick={() => onSelect(tenant)}
                >
                  <span className="inline-flex size-8 shrink-0 items-center justify-center rounded-md bg-accent-soft text-accent">
                    <Building2 className="size-4" aria-hidden="true" />
                  </span>
                  <span className="min-w-0 flex-1 truncate text-sm font-medium text-foreground">
                    {tenant.name}
                  </span>
                  <ArrowRight
                    className="size-4 shrink-0 text-muted-foreground"
                    aria-hidden="true"
                  />
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <div className="flex min-h-0 flex-1 flex-col items-center justify-center gap-2 p-4 text-center">
            <div className="text-sm font-medium">暂时没有可访问的租户</div>
            <p className="text-sm text-muted-foreground">请联系管理员为你分配租户。</p>
          </div>
        )}
      </Card>
    </section>
  )
}
