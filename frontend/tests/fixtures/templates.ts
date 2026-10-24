import type { ModelChoice, TemplateDetail } from '@/features/agent-templates/api'

export const templateModel: ModelChoice = {
  id: 3,
  sourceId: 2,
  name: '示例模型',
  sourceName: '团队连接',
  enabled: true,
  contextLimit: 131072,
  temperatureSupported: true,
  temperatureMin: 0,
  temperatureMax: 2,
}
export const template: TemplateDetail = {
  id: 9,
  name: '通用助手',
  description: '信息整理',
  enabled: true,
  modelId: 3,
  systemPrompt: '请保持清晰',
  temperature: null,
  contextBudgetTokens: null,
  summaryModelId: null,
  keepRecentMessages: 10,
  maxIterations: 0,
  timeoutMinutes: 0,
  revision: 2,
  updatedAt: '2026-10-24T12:00:00Z',
  model: templateModel,
  summaryModel: templateModel,
  skillBindings: [
    {
      skillId: 1,
      versionNumber: 1,
      name: '资料整理',
      description: '梳理材料',
      enabled: true,
      latestVersion: 2,
    },
  ],
  available: true,
  unavailableReasons: [],
}
