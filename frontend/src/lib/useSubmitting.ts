import { useRef, useState } from 'react'
import { toast } from 'sonner'
import { errorMessage } from './errors'
import { useIsMounted } from './useIsMounted'

// 防重复提交与 busy 状态的公共形态：任务串行执行，任务返回的字符串作为成功通知文案，
// 抛出的异常统一走 errorMessage + 全局错误通知，反馈策略不留在各调用方重复。
export function useSubmitting() {
  const submitting = useRef(false)
  const [busy, setBusy] = useState(false)
  const active = useIsMounted()

  async function run(task: () => Promise<string | void>, fallback: string): Promise<boolean> {
    if (submitting.current) return false
    submitting.current = true
    setBusy(true)
    try {
      const message = await task()
      if (active.current && message) toast.success(message)
      return true
    } catch (cause) {
      if (active.current) toast.error(errorMessage(cause, fallback))
      return false
    } finally {
      submitting.current = false
      if (active.current) setBusy(false)
    }
  }

  return { busy, run, active }
}
