import { useEffect, useRef, useState } from 'react'
import type { PageResult } from './api'

const PAGE_SIZE = 10

/** 分页请求带序号，过期的翻页或刷新响应不能覆盖新页面。 */
export function useAdminPage<T>(load: (page: number, size: number) => Promise<PageResult<T>>) {
  const [page, setPage] = useState(1)
  const [revision, setRevision] = useState(0)
  const [state, setState] = useState<{
    data: PageResult<T> | null
    error: string
    page: number
    revision: number
  }>({ data: null, error: '', page: 0, revision: -1 })
  const sequence = useRef(0)

  useEffect(() => {
    const requestId = ++sequence.current
    load(page, PAGE_SIZE)
      .then((data) => {
        if (requestId !== sequence.current) return
        const lastPage = Math.max(1, Math.ceil(data.total / PAGE_SIZE))
        if (page > lastPage) {
          setPage(lastPage)
          return
        }
        setState({ data, error: '', page, revision })
      })
      .catch((cause: unknown) => {
        if (requestId === sequence.current)
          setState({
            data: null,
            error: cause instanceof Error ? cause.message : '加载列表失败',
            page,
            revision,
          })
      })
    return () => {
      sequence.current += 1
    }
  }, [load, page, revision])

  const pending = state.page !== page || state.revision !== revision
  return {
    data: state.data,
    error: pending ? '' : state.error,
    pending,
    page,
    size: PAGE_SIZE,
    setPage,
    reload: () => setRevision((value) => value + 1),
  }
}
