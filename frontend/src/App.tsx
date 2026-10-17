import type { ReactNode } from 'react'
import './App.css'

type IconName = 'loop' | 'plus' | 'inbox' | 'search' | 'chevron' | 'settings' | 'clock' | 'spark' | 'copy' | 'check' | 'tool' | 'send' | 'info'

const iconPaths: Record<IconName, ReactNode> = {
  loop: <path d="M7.5 7.5h6.25a4.25 4.25 0 0 1 0 8.5H11m-4.5 0h-1a4.25 4.25 0 0 1 0-8.5H9m-1.5-2 2 2-2 2m6 8 2-2-2-2" />,
  plus: <path d="M12 5v14M5 12h14" />,
  inbox: <path d="M4 5.75A1.75 1.75 0 0 1 5.75 4h12.5A1.75 1.75 0 0 1 20 5.75v12.5A1.75 1.75 0 0 1 18.25 20H5.75A1.75 1.75 0 0 1 4 18.25zM4.5 14h4l1.5 2h4l1.5-2h4" />,
  search: <><circle cx="10.8" cy="10.8" r="6.3" /><path d="m16 16 4 4" /></>,
  chevron: <path d="m7 10 5 5 5-5" />,
  settings: <><circle cx="12" cy="12" r="3" /><path d="m19.4 15 .1.1a1.7 1.7 0 0 1-2.4 2.4l-.1-.1a1.7 1.7 0 0 0-2.9 1.2v.2a1.7 1.7 0 0 1-3.4 0v-.2a1.7 1.7 0 0 0-2.9-1.2l-.1.1a1.7 1.7 0 0 1-2.4-2.4l.1-.1a1.7 1.7 0 0 0-1.2-2.9H4a1.7 1.7 0 0 1 0-3.4h.2a1.7 1.7 0 0 0 1.2-2.9l-.1-.1a1.7 1.7 0 0 1 2.4-2.4l.1.1a1.7 1.7 0 0 0 2.9-1.2V4a1.7 1.7 0 0 1 3.4 0v.2a1.7 1.7 0 0 0 2.9 1.2l.1-.1a1.7 1.7 0 0 1 2.4 2.4l-.1.1a1.7 1.7 0 0 0 1.2 2.9h.2a1.7 1.7 0 0 1 0 3.4h-.2a1.7 1.7 0 0 0-1.2.9Z" /></>,
  clock: <><circle cx="12" cy="12" r="8.5" /><path d="M12 7v5l3.5 2" /></>,
  spark: <><path d="m12 3 1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z" /><path d="m19 16 .9 2.1L22 19l-2.1.9L19 22l-.9-2.1L16 19l2.1-.9L19 16Z" /></>,
  copy: <><rect x="8" y="8" width="11" height="12" rx="2" /><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h2" /></>,
  check: <path d="m5 12 4.5 4.5L19 7" />,
  tool: <><path d="M14.5 6.5a4 4 0 0 0-5.3 5.3L4 17l3 3 5.2-5.2a4 4 0 0 0 5.3-5.3l-2.7 2.7-3-3z" /><path d="m15 5 4 4" /></>,
  send: <><path d="m21 3-7.2 18-3.8-7-7-3.8L21 3Z" /><path d="M10 14 21 3" /></>,
  info: <><circle cx="12" cy="12" r="9" /><path d="M12 11v5m0-8h.01" /></>,
}

function Icon({ name, size = 20 }: { name: IconName; size?: number }) {
  return (
    <svg
      aria-hidden="true"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {iconPaths[name]}
    </svg>
  )
}

const recentSessions = [
  { id: 'sales', title: '汇总三家门店的销售额', time: '今天' },
  { id: 'report', title: '整理周报里的关键数据', time: '昨天' },
  { id: 'api', title: '分析 API 错误原因', time: '周一' },
]

const runSteps = [
  {
    id: 'request',
    number: '01',
    label: '模型请求',
    detail: '根据用户问题决定是否调用工具',
    status: '已完成',
  },
  {
    id: 'tool',
    number: '02',
    label: '工具调用',
    detail: 'calculator.evaluate',
    status: '已完成',
  },
  {
    id: 'observation',
    number: '03',
    label: '工具结果',
    detail: '计算结果返回给 Agent',
    status: '已完成',
  },
  {
    id: 'response',
    number: '04',
    label: '最终回复',
    detail: '根据工具结果回答用户',
    status: '已完成',
  },
]

function App() {
  return (
    <div className="app-shell">
      <aside className="sidebar" aria-label="主导航">
        <div className="brand-row">
          <span className="brand-mark"><Icon name="loop" size={22} /></span>
          <span className="brand-name">LoopCraft</span>
        </div>

        <button className="new-chat-button" type="button" disabled>
          <Icon name="plus" size={18} />
          <span>新建对话</span>
        </button>

        <div className="sidebar-section">
          <div className="section-label">工作区</div>
          <div className="nav-item nav-item-active" aria-current="page">
            <Icon name="inbox" size={18} />
            <span>Agent 工作台</span>
          </div>
        </div>

        <div className="sidebar-section recent-section">
          <div className="section-heading">
            <span className="section-label">最近会话</span>
            <button className="icon-button small-icon-button" type="button" aria-label="搜索会话" disabled>
              <Icon name="search" size={16} />
            </button>
          </div>
          <div className="session-list">
            {recentSessions.map((session, index) => (
              <div className={`session-item ${index === 0 ? 'session-item-active' : ''}`} key={session.id}>
                <span className="session-title">{session.title}</span>
                <span className="session-time">{session.time}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="sidebar-bottom">
          <div className="workspace-selector">
            <span className="workspace-avatar">L</span>
            <span className="workspace-copy">
              <strong>个人工作区</strong>
              <span>本地体验</span>
            </span>
            <Icon name="chevron" size={16} />
          </div>
        </div>
      </aside>

      <main className="workspace-main">
        <header className="topbar">
          <div className="breadcrumbs">
            <span>Agent 工作台</span>
            <span className="breadcrumb-divider">/</span>
            <strong>汇总三家门店的销售额</strong>
          </div>
          <div className="topbar-actions">
            <span className="demo-label"><span className="demo-dot" />静态演示</span>
            <button className="model-button" type="button" disabled>
              <span className="model-indicator" />
              模型未连接
              <Icon name="chevron" size={16} />
            </button>
          </div>
        </header>

        <div className="workspace-content">
          <section className="conversation" aria-label="对话演示">
            <div className="conversation-heading">
              <div className="eyebrow">REACT PLAYGROUND</div>
              <h1>看清 Agent 如何行动</h1>
              <p>从问题到工具调用，再到最终回答，每一步都清楚可见。</p>
            </div>

            <div className="message-list">
              <div className="user-message-row">
                <div className="user-message-meta">你 <span>14:32</span></div>
                <div className="user-message">
                  把三家门店的销售额加起来：A 店 1,280 元，B 店 960 元，C 店 1,750 元。
                </div>
              </div>

              <div className="assistant-message-row">
                <div className="assistant-avatar"><Icon name="loop" size={18} /></div>
                <div className="assistant-message-content">
                  <div className="assistant-message-meta">
                    <strong>LoopCraft</strong>
                    <span className="completed-label"><Icon name="check" size={14} />已完成</span>
                  </div>
                  <p>三家门店的销售额合计为 <strong>3,990 元</strong>。</p>
                  <p className="assistant-note">我调用了计算工具核对总和，右侧可以查看本次运行步骤。</p>
                  <div className="message-actions">
                    <button className="text-action" type="button" disabled aria-label="复制回复">
                      <Icon name="copy" size={16} />复制
                    </button>
                    <span className="message-separator" />
                    <span>示例回复</span>
                  </div>
                </div>
              </div>
            </div>

            <div className="composer-wrap">
              <form className="composer" onSubmit={(event) => event.preventDefault()}>
                <textarea aria-label="输入消息" placeholder="给 Agent 一个任务，观察它如何完成……" rows={2} />
                <div className="composer-footer">
                  <div className="composer-hint"><Icon name="info" size={16} />演示页面尚未连接模型</div>
                  <button className="send-button" type="submit" disabled aria-label="发送消息">
                    <Icon name="send" size={18} />
                    <span>发送</span>
                  </button>
                </div>
              </form>
              <p className="composer-disclaimer">当前内容为静态示例，不会发送请求或调用模型。</p>
            </div>
          </section>

          <aside className="trace-panel" aria-label="Agent 运行轨迹">
            <div className="trace-header">
              <div>
                <div className="trace-eyebrow">EXECUTION</div>
                <h2>运行轨迹</h2>
              </div>
              <span className="sample-badge">示例</span>
            </div>

            <div className="run-summary">
              <div className="run-summary-icon"><Icon name="spark" size={18} /></div>
              <div className="run-summary-copy">
                <strong>ReAct 执行</strong>
                <span>模型 → 工具 → 结果</span>
              </div>
              <span className="run-status"><span />完成</span>
            </div>

            <div className="trace-section-title">
              <span>执行步骤</span>
              <span>4 步</span>
            </div>

            <div className="step-list">
              {runSteps.map((step, index) => (
                <article className="step-item" key={step.id}>
                  <div className="step-rail">
                    <span className={`step-number ${index === 1 ? 'step-number-accent' : ''}`}>{step.number}</span>
                    {index < runSteps.length - 1 && <span className="step-line" />}
                  </div>
                  <div className="step-content">
                    <div className="step-title-row">
                      <strong>{step.label}</strong>
                      <span>{step.status}</span>
                    </div>
                    <p>{step.detail}</p>
                    {step.id === 'tool' && (
                      <div className="tool-call-card">
                        <div className="tool-card-heading"><Icon name="tool" size={16} />工具参数</div>
                        <code>{'{ "expression": "1280 + 960 + 1750" }'}</code>
                      </div>
                    )}
                    {step.id === 'observation' && (
                      <div className="observation-card">
                        <span>返回结果</span>
                        <strong>3990</strong>
                      </div>
                    )}
                  </div>
                </article>
              ))}
            </div>

            <div className="trace-footer">
              <div className="trace-footer-icon"><Icon name="clock" size={16} /></div>
              <div>
                <strong>演示运行记录</strong>
                <span>仅展示结构，不代表真实耗时</span>
              </div>
            </div>
          </aside>
        </div>
      </main>
    </div>
  )
}

export default App
