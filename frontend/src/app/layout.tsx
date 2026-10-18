import type { Metadata } from 'next'
import type { ReactNode } from 'react'
import { Toaster } from 'sonner'
import './globals.css'

export const metadata: Metadata = {
  title: 'LoopCraft · 后台配置',
  description: 'LoopCraft 租户、账号与成员管理',
}

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body>
        {children}
        <Toaster
          position="top-right"
          toastOptions={{
            classNames: {
              toast: 'border border-border bg-surface text-foreground shadow-sm',
              title: 'text-foreground',
              description: 'text-muted-foreground',
            },
          }}
        />
      </body>
    </html>
  )
}
