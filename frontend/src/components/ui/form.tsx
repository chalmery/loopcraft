'use client'

import type { ComponentProps } from 'react'
import { toast } from 'sonner'

type Control = HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement

function validationMessage(field: Control) {
  const label = field.labels?.[0]
  const name =
    field.getAttribute('aria-label') ||
    Array.from(label?.childNodes ?? [])
      .filter((node) => node.nodeType === 3 || node.nodeName === 'SPAN')
      .map((node) => node.textContent?.trim())
      .find(Boolean) ||
    '此项'
  const { validity, value } = field
  if (validity.valueMissing || (field.required && !value.trim())) return `请填写${name}`
  if ('minLength' in field && value && field.minLength > 0 && value.length < field.minLength)
    return `${name}至少需要 ${field.minLength} 个字符`
  if ('maxLength' in field && field.maxLength > 0 && value.length > field.maxLength)
    return `${name}不能超过 ${field.maxLength} 个字符`
  if (validity.typeMismatch) return `请输入有效的${name}`
  if (validity.patternMismatch) return `${name}格式不正确，请按提示填写`
  if (!validity.valid) return `${name}填写不正确，请检查`
  return ''
}

// 保留 HTML 字段约束，统一用全局通知代替浏览器原生校验气泡。
export function Form({
  onSubmit,
  onValidationError,
  validate,
  ...props
}: Omit<ComponentProps<'form'>, 'noValidate'> & {
  onValidationError?: () => void
  validate?: () => boolean
}) {
  return (
    <form
      {...props}
      noValidate
      onSubmit={(event) => {
        if (validate) {
          if (!validate()) event.preventDefault()
          else onSubmit?.(event)
          return
        }
        const fields = event.currentTarget.querySelectorAll<Control>('input, textarea, select')
        for (const field of fields) {
          if (!field.willValidate) continue
          const message = validationMessage(field)
          if (!message) continue
          event.preventDefault()
          toast.error(message)
          onValidationError?.()
          requestAnimationFrame(() => {
            if (field.isConnected) field.focus()
          })
          return
        }
        onSubmit?.(event)
      }}
    />
  )
}
