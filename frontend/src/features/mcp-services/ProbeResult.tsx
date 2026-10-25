import { Badge } from '@/components/ui/badge'
import { TRANSPORT_LABELS, type TestResult, type Transport } from './api'

/** 连接探测结果：失败只显原因，成功展示握手信息与工具清单（前缀名在接入循环后生效）。 */
export function ProbeResult({ result }: { result: TestResult }) {
  if (!result.ok) {
    return (
      <div
        role="alert"
        className="rounded-lg border border-border bg-danger-soft p-3 text-sm text-danger"
      >
        连接失败：{result.reason ?? '未知原因'}
      </div>
    )
  }
  return (
    <output className="block space-y-2 rounded-lg border border-border bg-success-soft p-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant="success">连接成功</Badge>
        {result.transport && (
          <Badge variant="outline">
            传输 {TRANSPORT_LABELS[result.transport as Transport] ?? result.transport}
          </Badge>
        )}
        {result.protocolVersion && <Badge variant="outline">协议 {result.protocolVersion}</Badge>}
        {result.serverName && (
          <Badge variant="outline">
            {result.serverName}
            {result.serverVersion ? ` ${result.serverVersion}` : ''}
          </Badge>
        )}
      </div>
      {result.tools.length > 0 ? (
        <ul className="space-y-1 font-mono text-xs break-all text-text-secondary">
          {result.tools.map((tool) => (
            <li key={tool.name}>
              {tool.name}
              {tool.description ? (
                <span className="font-sans text-muted-foreground"> · {tool.description}</span>
              ) : null}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">该服务未暴露任何工具。</p>
      )}
      <p className="text-xs text-muted-foreground">
        探测不维持连接；工具清单将在 Agent 受理时按连接缓存解析。
      </p>
    </output>
  )
}
