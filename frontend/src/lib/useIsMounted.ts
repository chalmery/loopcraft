import { useEffect, useRef } from 'react'

// 严格模式下 effect 会执行两次，挂载标记必须在 effect 内重新置真。
export function useIsMounted() {
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  return mounted
}
