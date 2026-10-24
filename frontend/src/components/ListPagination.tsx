import { Button } from '@/components/ui/button'
import {
  Pagination,
  PaginationContent,
  PaginationEllipsis,
  PaginationItem,
} from '@/components/ui/pagination'

// 页数不超过 7 时全部展示，否则压缩为 1 … p-1 p p+1 … N。
function pageItems(page: number, pages: number): Array<number | 'gap'> {
  if (pages <= 7) return Array.from({ length: pages }, (_, index) => index + 1)
  const middle = [page - 1, page, page + 1].filter((item) => item > 1 && item < pages)
  const items: Array<number | 'gap'> = [1]
  let previous = 1
  for (const item of middle) {
    if (item - previous > 1) items.push('gap')
    items.push(item)
    previous = item
  }
  if (pages - previous > 1) items.push('gap')
  items.push(pages)
  return items
}

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
          {pageItems(page, pages).map((item) => (
            <PaginationItem key={item} className="hidden sm:list-item">
              {item === 'gap' ? (
                <PaginationEllipsis />
              ) : (
                <Button
                  variant={item === page ? 'secondary' : 'ghost'}
                  size="icon-sm"
                  aria-label={`第 ${item} 页`}
                  aria-current={item === page ? 'page' : undefined}
                  disabled={pending}
                  onClick={() => onPage(item)}
                >
                  {item}
                </Button>
              )}
            </PaginationItem>
          ))}
          <PaginationItem>
            <span className="px-2 text-sm sm:hidden" aria-live="polite">
              {page} / {pages}
            </span>
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
