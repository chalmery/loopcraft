import { useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { Plus, RefreshCw } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/components/ui/alert-dialog'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Pagination, PaginationContent, PaginationItem } from '@/components/ui/pagination'

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

export function EditorSheet({
  title,
  description,
  busy,
  onClose,
  children,
}: {
  title: string
  description: string
  busy: boolean
  onClose: () => void
  children: ReactNode
}) {
  const [opener] = useState(() => document.activeElement)
  return (
    <Sheet
      open
      onOpenChange={(open) => {
        if (!open && !busy) onClose()
      }}
    >
      <SheetContent
        showCloseButton={!busy}
        onCloseAutoFocus={(event) => {
          event.preventDefault()
          if (opener instanceof HTMLElement && opener.isConnected) opener.focus()
          else document.querySelector<HTMLElement>('[data-admin-add]')?.focus()
        }}
      >
        <SheetHeader>
          <SheetTitle>{title}</SheetTitle>
          <SheetDescription>{description}</SheetDescription>
        </SheetHeader>
        {children}
      </SheetContent>
    </Sheet>
  )
}

export function DeleteRecord({
  name,
  description,
  disabled,
  onDelete,
  onDeleted,
}: {
  name: string
  description: string
  disabled?: boolean
  onDelete: () => Promise<unknown>
  onDeleted: () => void
}) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  async function confirm() {
    setBusy(true)
    setError('')
    try {
      await onDelete()
      setOpen(false)
      toast.success('删除成功')
      onDeleted()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '删除失败')
    } finally {
      setBusy(false)
    }
  }
  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (!busy) {
          setOpen(next)
          setError('')
        }
      }}
    >
      <AlertDialogTrigger asChild>
        <Button variant="danger-ghost" size="sm" disabled={disabled}>
          删除
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>确认删除 {name}？</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        {error && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={busy}>取消</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={busy}
            onClick={(event) => {
              event.preventDefault()
              void confirm()
            }}
          >
            {busy ? '删除中…' : '确认删除'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
