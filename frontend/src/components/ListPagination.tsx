import { Button } from '@/components/ui/button'
import { Pagination, PaginationContent, PaginationItem } from '@/components/ui/pagination'

export function ListPagination({
  page,
  size,
  total,
  pending,
  onPage,
}: {
  page: number
  size: number
  total: number
  pending: boolean
  onPage: (page: number) => void
}) {
  const pages = Math.max(1, Math.ceil(total / size))
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border px-4 py-3">
      <p className="text-sm text-muted-foreground">
        共 {total} 条 · 每页 {size} 条
      </p>
      <Pagination className="mx-0 w-auto" aria-label="列表分页">
        <PaginationContent>
          <PaginationItem>
            <Button
              variant="outline"
              size="sm"
              disabled={pending || page <= 1}
              onClick={() => onPage(page - 1)}
            >
              上一页
            </Button>
          </PaginationItem>
          <PaginationItem>
            <span className="px-2 text-sm" aria-live="polite">
              {page} / {pages}
            </span>
          </PaginationItem>
          <PaginationItem>
            <Button
              variant="outline"
              size="sm"
              disabled={pending || page >= pages}
              onClick={() => onPage(page + 1)}
            >
              下一页
            </Button>
          </PaginationItem>
        </PaginationContent>
      </Pagination>
    </div>
  )
}
