import { afterEach, beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { act } from 'react'
import type { Root } from 'react-dom/client'
import { Button } from '@/components/ui/button'
import { createDom } from '../../support/dom'

let root: Root
let container: HTMLDivElement
let dispose: () => Promise<void>
beforeEach(async () => {
  const dom = await createDom()
  root = dom.root
  container = dom.container
  dispose = dom.dispose
})
afterEach(async () => dispose())
test('asChild 只向 Radix Slot 传递一个元素，确认框按钮可以挂载和点击', async () => {
  let clicked = 0
  await act(async () =>
    root.render(
      <Button
        asChild
        onClick={() => {
          clicked++
        }}
      >
        <a href="#test">继续编辑</a>
      </Button>,
    ),
  )
  const link = container.querySelector('a')
  assert.ok(link)
  assert.equal(link.textContent, '继续编辑')
  await act(async () => link.click())
  assert.equal(clicked, 1)
})
test('普通按钮保留加载图标与禁用语义', async () => {
  await act(async () => root.render(<Button loading>保存中</Button>))
  assert.equal(container.querySelector('button')?.disabled, true)
  assert.equal(container.querySelectorAll('svg').length, 1)
})
