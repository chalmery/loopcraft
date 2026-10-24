import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

// 合并条件类名，并让调用方覆盖同类 Tailwind 样式。
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

// 统一拼装查询串；值为 undefined 的参数不进入 URL。
export function queryString(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [name, value] of Object.entries(params))
    if (value !== undefined) search.set(name, String(value))
  const encoded = search.toString()
  return encoded ? `?${encoded}` : ''
}
