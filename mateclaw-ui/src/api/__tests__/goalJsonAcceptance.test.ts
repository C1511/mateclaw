import { afterEach, expect, it, vi } from 'vitest'
import { http } from '@/api/index'
import { goalJsonAcceptanceApi } from '@/api/goalJsonAcceptance'
afterEach(() => vi.restoreAllMocks())
it('keeps goal IDs and optimistic revisions as exact strings', () => {
  const get = vi.spyOn(http, 'get').mockResolvedValue({} as never)
  const put = vi.spyOn(http, 'put').mockResolvedValue({} as never)
  const goal = '9223372036854775801'
  const data = { expectedRevision: '9223372036854775802', artifactSlot: 'report', requiredFields: ['summary'] }
  goalJsonAcceptanceApi.get(goal); goalJsonAcceptanceApi.configure(goal, 'report-fields', data)
  expect(get).toHaveBeenCalledWith(`/goals/${goal}/json-acceptance`)
  expect(put).toHaveBeenCalledWith(`/goals/${goal}/json-acceptance/requirements/report-fields`, data)
})
