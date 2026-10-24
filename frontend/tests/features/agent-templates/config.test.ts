import { test } from 'node:test'
import assert from 'node:assert/strict'
import { availability, configOf, draftOf, validateDraft } from '@/features/agent-templates/config'
import { template, templateModel } from '../../fixtures/templates'

test('空预算和摘要保留继承关系，零限制与小数分钟完整传输', () => {
  const draft = draftOf(template)
  draft.timeoutMinutes = '0.5'
  const config = configOf(draft)
  assert.equal(config.contextBudgetTokens, null)
  assert.equal(config.summaryModelId, null)
  assert.equal(config.temperature, null)
  assert.equal(config.maxIterations, 0)
  assert.equal(config.timeoutMinutes, 0.5)
  assert.equal(validateDraft(draft).length, 0)
})
test('切换较小模型后保留显式预算并提示对应页签，不静默截断', () => {
  const draft = draftOf(template)
  draft.contextBudgetTokens = '65536'
  draft.model = { ...templateModel, contextLimit: 32768 }
  assert.equal(draft.contextBudgetTokens, '65536')
  assert.deepEqual(
    validateDraft(draft).map((e) => [e.field, e.tab]),
    [['contextBudgetTokens', 'context']],
  )
  draft.contextBudgetTokens = ''
  assert.equal(validateDraft(draft).length, 0)
})
test('负数、小数循环与空必填参数不能变成默认零，多个栏目一起校验', () => {
  const draft = draftOf(template)
  draft.name = ''
  draft.maxIterations = '1.5'
  draft.keepRecentMessages = '0'
  draft.timeoutMinutes = ''
  assert.deepEqual(
    validateDraft(draft).map((e) => e.field),
    ['name', 'keepRecentMessages', 'maxIterations', 'timeoutMinutes'],
  )
})
test('不可用依赖和未知能力允许保存，但显式不支持温度时要求清空', () => {
  const draft = draftOf(template)
  draft.model = { ...templateModel, enabled: false, contextLimit: null, temperatureSupported: null }
  draft.skillBindings = draft.skillBindings.map((s) => ({ ...s, enabled: false }))
  assert.equal(validateDraft(draft).length, 0)
  assert.equal(availability(draft).length, 3)
  draft.model = { ...templateModel, temperatureSupported: false }
  draft.temperature = '0.5'
  assert.equal(validateDraft(draft)[0]?.field, 'temperature')
})
