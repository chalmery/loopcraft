import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

export function Field({
  label,
  name,
  type = 'text',
  required = true,
  minLength,
  maxLength,
  pattern,
  autoComplete,
  placeholder,
}: {
  label: string
  name: string
  type?: string
  required?: boolean
  minLength?: number
  maxLength?: number
  pattern?: string
  autoComplete?: string
  placeholder?: string
}) {
  return (
    <Label className="grid gap-2 text-sm font-medium text-secondary">
      <span>{label}</span>
      <Input
        name={name}
        type={type}
        required={required}
        minLength={minLength}
        maxLength={maxLength}
        pattern={pattern}
        autoComplete={autoComplete}
        placeholder={placeholder}
      />
    </Label>
  )
}
