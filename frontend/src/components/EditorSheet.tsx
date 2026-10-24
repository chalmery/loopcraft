import { useState, type ReactNode } from 'react'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'

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
  const [opener] = useState(() => (typeof document === 'undefined' ? null : document.activeElement))
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
