import { Window } from 'happy-dom'
import { act } from 'react'
import { clearCsrf } from '@/lib/api'

/** 每个用例独立安装 DOM，全局对象、fetch、渲染树和定时器在结束时恢复。 */
export async function createDom() {
  const window = new Window()
  const originalFetch = globalThis.fetch
  const descriptors = new Map<string, PropertyDescriptor | undefined>()
  for (const [name, value] of Object.entries({
    window,
    document: window.document,
    HTMLElement: window.HTMLElement,
    HTMLButtonElement: window.HTMLButtonElement,
    HTMLFormElement: window.HTMLFormElement,
    HTMLInputElement: window.HTMLInputElement,
    HTMLTextAreaElement: window.HTMLTextAreaElement,
    Element: window.Element,
    Node: window.Node,
    Event: window.Event,
    KeyboardEvent: window.KeyboardEvent,
    FormData: window.FormData,
    Blob: window.Blob,
    File: window.File,
    getComputedStyle: window.getComputedStyle.bind(window),
    requestAnimationFrame: window.requestAnimationFrame.bind(window),
    cancelAnimationFrame: window.cancelAnimationFrame.bind(window),
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
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
