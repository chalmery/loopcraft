import { useState } from 'react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Badge } from '@/components/ui/badge'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { SearchInput } from '@/components/SearchInput'
import { ErrorState } from '@/components/ErrorState'
import { ListPagination } from '@/components/ListPagination'
import { usePagedList } from '@/lib/usePagedList'
import { useDebouncedValue } from '@/lib/useDebouncedValue'
import { skillApi } from '@/features/skills/api'
import type { SkillBinding } from './api'

function VersionPicker({
  tenantId,
  binding,
  disabled,
  onChange,
}: {
  tenantId: number
  binding: SkillBinding
  disabled: boolean
  onChange: (version: number) => void
}) {
  const list = usePagedList(
    (page) => skillApi.versions(tenantId, binding.skillId, page),
    [tenantId, binding.skillId],
  )
  const numbers = [
    ...new Set([binding.versionNumber, ...(list.data?.records.map((v) => v.number) ?? [])]),
  ].sort((a, b) => b - a)
  return (
    <Select
      value={String(binding.versionNumber)}
      disabled={disabled || !binding.enabled}
      onValueChange={(v) => onChange(Number(v))}
    >
      <SelectTrigger size="sm" aria-label={`${binding.name}版本`} className="w-32">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {numbers.map((v) => (
          <SelectItem key={v} value={String(v)}>
            v{v}
            {v === binding.latestVersion ? ' · 最新' : ''}
          </SelectItem>
        ))}
        {list.loading && <p className="p-2 text-xs text-muted-foreground">正在加载版本…</p>}
        {list.error && <ErrorState message={list.error} onRetry={list.reload} />}
        <ListPagination
          page={list.page}
          size={list.data?.size ?? 20}
          total={list.data?.total ?? 0}
          pending={list.loading}
          onPage={list.setPage}
        />
      </SelectContent>
    </Select>
  )
}

export function SkillPicker({
  tenantId,
  value,
  onChange,
  disabled,
}: {
  tenantId: number
  value: SkillBinding[]
  onChange: (value: SkillBinding[]) => void
  disabled: boolean
}) {
  const [query, setQuery] = useState('')
  const search = useDebouncedValue(query)
  const list = usePagedList((page) => skillApi.list(tenantId, page, search), [tenantId, search])
  return (
    <div className="grid items-start gap-5 lg:grid-cols-2">
      <Card>
        <div>
          <h2 className="text-base font-semibold">Skill</h2>
          <p className="mt-1 text-xs text-muted-foreground">选择当前租户的 Skill，添加到模板。</p>
        </div>
        <SearchInput
          label="搜索 Skill"
          placeholder="搜索名称或描述"
          value={query}
          onChange={(q) => {
            setQuery(q)
            list.setPage(1)
          }}
          className="relative w-full"
        />
        {list.error && !list.loading ? (
          <ErrorState message={list.error} onRetry={list.reload} />
        ) : list.loading ? (
          <p aria-live="polite" className="py-8 text-sm text-muted-foreground">
            正在加载 Skill…
          </p>
        ) : list.data?.records.length ? (
          <div className="divide-y divide-border">
            {list.data.records.map((skill) => {
              const selected = value.some((s) => s.skillId === skill.id)
              return (
                <label
                  key={skill.id}
                  htmlFor={`template-skill-${skill.id}`}
                  className="flex cursor-pointer items-start gap-3 py-4 first:pt-0 last:pb-0"
                >
                  <Checkbox
                    id={`template-skill-${skill.id}`}
                    aria-label={`选择 ${skill.name}`}
                    className="mt-1"
                    checked={selected}
                    disabled={
                      disabled ||
                      (!skill.enabled && !selected) ||
                      (!selected && value.length >= 200)
                    }
                    onCheckedChange={(checked) => {
                      if (checked)
                        onChange([
                          ...value,
                          {
                            skillId: skill.id,
                            versionNumber: skill.latestVersion,
                            name: skill.name,
                            description: skill.description,
                            enabled: skill.enabled,
                            latestVersion: skill.latestVersion,
                          },
                        ])
                      else onChange(value.filter((s) => s.skillId !== skill.id))
                    }}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="font-medium">{skill.name}</span>
                    <span className="ml-2 text-xs text-muted-foreground">
                      {skill.enabled ? `v${skill.latestVersion}` : '已停用'}
                    </span>
                    <span className="mt-1 block text-xs leading-5 break-words text-muted-foreground">
                      {skill.description}
                    </span>
                  </span>
                </label>
              )
            })}
          </div>
        ) : (
          <p className="py-8 text-center text-sm text-muted-foreground">
            {search ? '没有匹配的 Skill' : '当前租户还没有 Skill'}
          </p>
        )}
        <ListPagination
          page={list.page}
          size={list.data?.size ?? 12}
          total={list.data?.total ?? 0}
          pending={list.loading}
          onPage={list.setPage}
        />
      </Card>
      <Card>
        <div>
          <h2 className="text-base font-semibold">
            已选 Skill{' '}
            <span className="ml-1 text-sm font-normal text-muted-foreground">{value.length}</span>
          </h2>
          <p className="mt-1 text-xs text-muted-foreground">固定内容版本，需要时手动切换更新。</p>
        </div>
        {value.length ? (
          <div className="divide-y divide-border">
            {value.map((binding) => (
              <div key={binding.skillId} className="space-y-3 py-4 first:pt-0 last:pb-0">
                <div>
                  <span className="font-medium">{binding.name}</span>
                  {!binding.enabled && (
                    <Badge
                      className="ml-2 border-transparent bg-warning-soft text-warning"
                      variant="outline"
                    >
                      已停用
                    </Badge>
                  )}
                  <p className="mt-1 text-xs leading-5 break-words text-muted-foreground">
                    {binding.description}
                  </p>
                  {binding.versionNumber < binding.latestVersion && (
                    <p className="mt-1 text-xs text-secondary">
                      有新版本 v{binding.latestVersion}，当前固定 v{binding.versionNumber}
                    </p>
                  )}
                </div>
                <div className="flex items-center justify-end gap-2">
                  <VersionPicker
                    tenantId={tenantId}
                    binding={binding}
                    disabled={disabled}
                    onChange={(versionNumber) =>
                      onChange(
                        value.map((s) =>
                          s.skillId === binding.skillId ? { ...s, versionNumber } : s,
                        ),
                      )
                    }
                  />
                  <Button
                    type="button"
                    size="icon-sm"
                    variant="ghost"
                    aria-label={`移除 ${binding.name}`}
                    disabled={disabled}
                    onClick={() => onChange(value.filter((s) => s.skillId !== binding.skillId))}
                  >
                    <X aria-hidden />
                  </Button>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="rounded-lg border border-dashed border-border px-5 py-12 text-center">
            <p className="text-sm font-medium">还没有添加 Skill</p>
            <p className="mt-2 text-xs text-muted-foreground">选择需要的 Skill，也可以保持为空。</p>
          </div>
        )}
      </Card>
    </div>
  )
}
