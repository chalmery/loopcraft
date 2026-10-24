import { useState } from 'react'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'

// 未保存离开的公共守卫：忙时拒绝并可提示，脏时弹确认；执行导航前统一丢弃未保存状态，
// 干净路径下丢弃是幂等操作（如退出编辑态、清掉已为假的脏标记）。
export function useNavigationGuard({
  locked,
  busy,
  onBusy,
  onDiscard,
  title,
  description,
}: {
  locked: boolean
  busy: boolean
  onBusy?: () => void
  onDiscard: () => void
  title: string
  description: string
}) {
  const [pending, setPending] = useState<(() => void) | null>(null)

  function guard(action: () => void) {
    if (busy) {
      onBusy?.()
      return
    }
    if (locked) setPending(() => action)
    else {
      onDiscard()
      action()
    }
  }

  const dialog = (
    <AlertDialog
      open={pending !== null}
      onOpenChange={(open) => {
        if (!open) setPending(null)
      }}
    >
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>继续编辑</AlertDialogCancel>
          <AlertDialogAction
            onClick={() => {
              const action = pending
              setPending(null)
              onDiscard()
              action?.()
            }}
          >
            放弃并继续
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )

  return { guard, dialog }
}
