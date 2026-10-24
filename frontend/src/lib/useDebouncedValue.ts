import { useEffect, useState } from 'react'

// 即时搜索的输入防抖：值稳定超过 delay 毫秒后才对外可见。
export function useDebouncedValue<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(timer)
  }, [value, delay])
  return debounced
}
