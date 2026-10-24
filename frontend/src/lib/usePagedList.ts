import { useEffect, useRef, useState } from 'react'
import { errorMessage } from './errors'

type Paged = { total: number; size: number }

// 分页列表的共享状态机：请求带序号与身份 key，过期响应不得覆盖新状态；
// 请求成功后落在超出末页的页码时回退到最后一页并重新请求；失败保留上一次成功的数据。
export function usePagedList<T extends Paged>(
  fetchPage: (page: number) => Promise<T>,
  deps: unknown[],
) {
  const [page, setPage] = useState(1)
  const [revision, setRevision] = useState(0)
  const key = JSON.stringify([...deps, page, revision])
  const [state, setState] = useState<{ key: string; data: T | null; error: string }>({
    key: '',
    data: null,
    error: '',
  })
  const sequence = useRef(0)
  const latest = useRef(fetchPage)
  useEffect(() => {
    latest.current = fetchPage
  })
  useEffect(() => {
    const requestId = ++sequence.current
    let stale = false
    latest.current(page).then(
      (data) => {
        if (stale || requestId !== sequence.current) return
        const lastPage = Math.max(1, Math.ceil(data.total / data.size))
        if (page > lastPage) {
          setPage(lastPage)
          return
        }
        setState({ key, data, error: '' })
      },
      (cause: unknown) => {
        if (!stale && requestId === sequence.current)
          setState((current) => ({
            key,
            data: current.data,
            error: errorMessage(cause, '加载列表失败'),
          }))
      },
    )
    return () => {
      stale = true
    }
  }, [key, page])
  const loading = state.key !== key
  return {
    data: state.data,
    loading,
    error: loading ? '' : state.error,
    page,
    setPage,
    reload: () => setRevision((value) => value + 1),
  }
}
