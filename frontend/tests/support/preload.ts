import { Window } from 'happy-dom'

// bun test 进程启动时预置一个空文档；createDom 会在各用例内安装并恢复自己的实例。
if (!globalThis.document) {
  const window = new Window()
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    writable: true,
    value: window.document,
  })
}
