import { useEffect, useRef, useState } from 'react'
import { toast } from 'sonner'
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
  const active = useRef(true)
  const submitting = useRef(false)
  useEffect(() => {
    active.current = true
    return () => {
      active.current = false
    }
  }, [])
  async function confirm() {
    if (submitting.current) return
    submitting.current = true
    setBusy(true)
    try {
      await onDelete()
      if (!active.current) return
      setOpen(false)
      toast.success('删除成功')
      onDeleted()
    } catch (cause) {
      if (active.current) toast.error(cause instanceof Error ? cause.message : '删除失败')
    } finally {
      submitting.current = false
      if (active.current) setBusy(false)
    }
  }
  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (!busy) {
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
