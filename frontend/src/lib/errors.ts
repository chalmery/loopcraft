// 统一异常文案提取：未知异常回退到调用方给定的默认提示。
export function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof Error && cause.message ? cause.message : fallback
}
