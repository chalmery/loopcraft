import { useEffect, useState } from 'react'
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
import { useSubmitting } from '@/lib/useSubmitting'

export function DeleteRecord({
  name,
  description,
  disabled,
  onDelete,
  onDeleted,
  onPendingChange,
}: {
  name: string
  description: string
  disabled?: boolean
  onDelete: () => Promise<unknown>
  onDeleted: () => void
  onPendingChange?: (pending: boolean) => void
}) {
  const submit = useSubmitting()
  const [open, setOpen] = useState(false)
  useEffect(() => {
    onPendingChange?.(submit.busy)
    return () => onPendingChange?.(false)
  }, [submit.busy, onPendingChange])
  async function confirm() {
    await submit.run(async () => {
      await onDelete()
      if (!submit.active.current) return
      setOpen(false)
      onDeleted()
      return '删除成功'
    }, '删除失败')
  }
  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (!submit.busy) {
          setOpen(next)
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
        <AlertDialogFooter>
          <AlertDialogCancel disabled={submit.busy}>取消</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={submit.busy}
            onClick={(event) => {
              event.preventDefault()
              void confirm()
            }}
          >
            {submit.busy ? '删除中…' : '确认删除'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
