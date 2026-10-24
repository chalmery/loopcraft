import { useState } from 'react'
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { SearchInput } from '@/components/SearchInput'
import { ListPagination } from '@/components/ListPagination'
import { ErrorState } from '@/components/ErrorState'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { usePagedList } from '@/lib/usePagedList'
import { templateApi, type ModelChoice } from './api'

export function ModelPicker({
  tenantId,
  value,
  onChange,
  disabled,
  summary = false,
  invalid = false,
}: {
  tenantId: number
  value: ModelChoice | null
  onChange: (value: ModelChoice | null) => void
  disabled: boolean
  summary?: boolean
  invalid?: boolean
}) {
  const [query, setQuery] = useState('')
  const search = useDebouncedValue(query)
  const list = usePagedList(
    (page) => templateApi.models(tenantId, page, search),
    [tenantId, search],
  )
  const records = list.data?.records ?? []
  const choices = value && !records.some((m) => m.id === value.id) ? [value, ...records] : records
  const groups = [...new Set(choices.map((m) => m.sourceName))]
  return (
    <Select
      value={value ? String(value.id) : 'follow'}
      disabled={disabled}
      onValueChange={(id) => {
        if (id === 'follow') onChange(null)
        else {
          const next = choices.find((m) => String(m.id) === id)
          if (next) onChange(next)
        }
      }}
    >
      <SelectTrigger
        className="w-full"
        id={summary ? 'summaryModelId' : 'modelId'}
        aria-label={summary ? '摘要模型' : '运行模型'}
        aria-invalid={invalid}
        aria-describedby={`${summary ? 'summaryModelId' : 'modelId'}-hint`}
      >
        <SelectValue placeholder="选择运行模型" />
      </SelectTrigger>
      <SelectContent>
        <div className="p-2">
          <SearchInput
            label={summary ? '搜索摘要模型' : '搜索运行模型'}
            value={query}
            onChange={(q) => {
              setQuery(q)
              list.setPage(1)
            }}
            placeholder="搜索模型名称或 ID"
            className="relative w-full"
            onKeyDown={(event) => event.stopPropagation()}
          />
        </div>
        <SelectItem value="follow" disabled={!summary}>
          {summary ? '与运行模型相同' : '选择运行模型'}
        </SelectItem>
        {list.loading ? (
          <p className="px-3 py-2 text-xs text-muted-foreground">正在加载模型…</p>
        ) : list.error ? (
          <ErrorState message={list.error} onRetry={list.reload} />
        ) : (
          <>
            {groups.map((group) => (
              <SelectGroup key={group}>
                <SelectLabel>{group}</SelectLabel>
                {choices
                  .filter((m) => m.sourceName === group)
                  .map((model) => (
                    <SelectItem key={model.id} value={String(model.id)} disabled={!model.enabled}>
                      {model.name}
                      {!model.enabled ? '（已停用）' : ''}
                    </SelectItem>
                  ))}
              </SelectGroup>
            ))}
            {!records.length && (
              <p className="px-3 py-2 text-xs text-muted-foreground">
                没有匹配的模型，可在模型接入添加。
              </p>
            )}
            <ListPagination
              page={list.page}
              size={list.data?.size ?? 20}
              total={list.data?.total ?? 0}
              pending={list.loading}
              onPage={list.setPage}
            />
          </>
        )}
      </SelectContent>
    </Select>
  )
}
