import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

// 合并条件类名，并让调用方覆盖同类 Tailwind 样式。
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
