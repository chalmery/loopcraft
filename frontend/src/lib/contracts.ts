import type { Validator } from './api'

export type Page<T> = { records: T[]; total: number; page: number; size: number }
export const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null
export const isNatural = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
export const isPositiveInteger = (value: unknown): value is number => isNatural(value) && value > 0
export function isPageOf<T>(validate: Validator<T>): Validator<Page<T>> {
  return (value): value is Page<T> =>
    isRecord(value) &&
    Array.isArray(value.records) &&
    value.records.every(validate) &&
    isNatural(value.total) &&
    isPositiveInteger(value.page) &&
    isPositiveInteger(value.size)
}
