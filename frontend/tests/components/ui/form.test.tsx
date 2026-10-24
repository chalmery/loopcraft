import { createDom } from '../../support/dom'
import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { act } from 'react'
import type { Root } from 'react-dom/client'
import { toast } from 'sonner'
import { Login } from '@/features/auth/Login'
import { SkillCreator } from '@/features/skills/SkillCreator'
import { Form } from '@/components/ui/form'

let dispose: () => Promise<void>
let container: HTMLDivElement
let root: Root
let notifications = 0
beforeEach(async () => {
  const dom = await createDom()
  container = dom.container
  root = dom.root
  dispose = dom.dispose
  notifications = toast.getHistory().length
})
afterEach(async () => {
  await dispose()
})
function messages() {
  return toast
    .getHistory()
    .slice(notifications)
    .flatMap((item) => ('title' in item ? [item.title] : []))
}
async function submit() {
  await act(async () => {
    container
      .querySelector('form')
      ?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await new Promise((resolve) => setTimeout(resolve, 30))
  })
}

test('空白必填项只提示首个错误，不提交并定位字段', async () => {
  let submitted = false
  await act(async () =>
    root.render(
      <Form
        onSubmit={() => {
          submitted = true
        }}
      >
        <label>
          显示名称
          <input name="name" required defaultValue="   " />
        </label>
        <label>
          描述
          <input required />
        </label>
      </Form>,
    ),
  )
  await submit()
  assert.equal(messages().length, 1)
  assert.equal(messages()[0], '请填写显示名称')
  assert.equal(submitted, false)
  assert.equal(container.querySelector('form')?.noValidate, true)
  assert.equal(document.activeElement === container.querySelector('input'), true)
})

for (const constraint of [
  {
    label: 'Skill 标识',
    value: 'Invalid Slug',
    pattern: '[a-z0-9]+(-[a-z0-9]+)*',
    expected: 'Skill 标识格式不正确，请按提示填写',
  },
  {
    label: '新密码',
    value: 'short',
    type: 'password',
    minLength: 12,
    expected: '新密码至少需要 12 个字符',
  },
  { label: 'API 根地址', value: 'invalid-url', type: 'url', expected: '请输入有效的API 根地址' },
]) {
  test(`${constraint.label}约束通过全局提示反馈`, async () => {
    let submitted = false
    await act(async () =>
      root.render(
        <Form
          onSubmit={() => {
            submitted = true
          }}
        >
          <input
            aria-label={constraint.label}
            defaultValue={constraint.value}
            type={constraint.type}
            pattern={constraint.pattern}
            minLength={constraint.minLength}
          />
        </Form>,
      ),
    )
    await submit()
    assert.equal(messages()[0], constraint.expected)
    assert.equal(submitted, false)
  })
}

test('合法表单跳过禁用字段并正常提交', async () => {
  let submitted = false
  await act(async () =>
    root.render(
      <Form
        onSubmit={(event) => {
          event.preventDefault()
          submitted = true
        }}
      >
        <input required disabled />
        <input required minLength={12} defaultValue="valid-password" />
      </Form>,
    ),
  )
  await submit()
  assert.equal(submitted, true)
  assert.equal(toast.getHistory().length, notifications)
})

test('登录提交失败仅显示全局通知，保留输入', async () => {
  await act(async () =>
    root.render(
      <Login
        onLogin={async () => {
          throw new Error('登录失败测试')
        }}
      />,
    ),
  )
  const username = container.querySelector<HTMLInputElement>('[name="username"]')
  const password = container.querySelector<HTMLInputElement>('[name="password"]')
  assert.ok(username && password)
  username.value = 'test-user'
  password.value = 'test-password'
  await submit()
  assert.equal(messages()[0], '登录失败测试')
  assert.equal(container.querySelector('[role="alert"]') === null, true)
  assert.equal(username.value, 'test-user')
})

test('Skill 内容页提交缺少基本信息时切回字段所在页', async () => {
  await act(async () =>
    root.render(
      <SkillCreator
        tenantId={1}
        imported={null}
        onClose={() => {}}
        onSaved={() => {}}
        onDirtyChange={() => {}}
      />,
    ),
  )
  const content = Array.from(container.querySelectorAll<HTMLButtonElement>('[role="tab"]')).find(
    (item) => item.textContent === '文件与版本',
  )
  assert.ok(content)
  await act(async () => content.click())
  await submit()
  const info = Array.from(container.querySelectorAll('[role="tab"]')).find(
    (item) => item.textContent === '基本信息',
  )
  assert.equal(info?.getAttribute('aria-selected'), 'true')
  assert.equal(messages()[0], '请填写显示名称')
})
