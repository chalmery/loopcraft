import { Window } from 'happy-dom'
import { act } from 'react'
import { clearCsrf } from '@/lib/api'

/** 每个用例独立安装 DOM，全局对象、fetch、渲染树和定时器在结束时恢复。 */
export async function createDom() {
  const window = new Window()
  const originalFetch = globalThis.fetch
  const descriptors = new Map<string, PropertyDescriptor | undefined>()
  // Radix 的 Select/Dialog 等组件在 layout effect 里直接引用这些全局；
  // preload 已让 SSR 守卫放行，这里按测试窗口提供真实实现。
  const globals: Record<string, unknown> = {
    window,
    document: window.document,
    HTMLElement: window.HTMLElement,
    HTMLButtonElement: window.HTMLButtonElement,
    HTMLFormElement: window.HTMLFormElement,
    HTMLInputElement: window.HTMLInputElement,
    HTMLTextAreaElement: window.HTMLTextAreaElement,
    Element: window.Element,
    Node: window.Node,
    NodeFilter: window.NodeFilter,
    Event: window.Event,
    KeyboardEvent: window.KeyboardEvent,
    CustomEvent: window.CustomEvent,
    MouseEvent: window.MouseEvent,
    FormData: window.FormData,
    Blob: window.Blob,
    File: window.File,
    DocumentFragment: window.DocumentFragment,
    MutationObserver: window.MutationObserver,
    getComputedStyle: window.getComputedStyle.bind(window),
    requestAnimationFrame: window.requestAnimationFrame.bind(window),
    cancelAnimationFrame: window.cancelAnimationFrame.bind(window),
    IS_REACT_ACT_ENVIRONMENT: true,
  }
  if (typeof window.ResizeObserver === 'function') {
    globals.ResizeObserver = window.ResizeObserver
  } else {
    globals.ResizeObserver = class {
      observe() {}
      unobserve() {}
      disconnect() {}
    }
  }
  if (typeof window.PointerEvent === 'function') {
    globals.PointerEvent = window.PointerEvent
  }
  for (const [name, value] of Object.entries(globals)) {
    descriptors.set(name, Object.getOwnPropertyDescriptor(globalThis, name))
    Object.defineProperty(globalThis, name, { configurable: true, writable: true, value })
  }
  const container = document.createElement('div')
  document.body.append(container)
  const { createRoot } = await import('react-dom/client')
  const root = createRoot(container)
  async function dispose() {
    await act(async () => root.unmount())
    container.remove()
    await window.happyDOM.close()
    globalThis.fetch = originalFetch
    clearCsrf()
    for (const [name, descriptor] of descriptors) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor)
      else Reflect.deleteProperty(globalThis, name)
    }
  }
  return { window, container, root, dispose }
}
