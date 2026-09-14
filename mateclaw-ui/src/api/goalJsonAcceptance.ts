import { http } from './index'

export interface GoalJsonRequirement {
  criterionKey: string
  artifactSlot: string
  revision: string
  requiredFields: string[]
  configuredBy: string
}
export interface GoalJsonAcceptanceView { required: boolean; requirements: GoalJsonRequirement[] }
export interface ConfigureJsonRequirement { expectedRevision: string; artifactSlot: string; requiredFields: string[] }
export const goalJsonAcceptanceApi = {
  get: (goalId: string) => http.get<never, { data: GoalJsonAcceptanceView }>(`/goals/${encodeURIComponent(goalId)}/json-acceptance`),
  configure: (goalId: string, key: string, data: ConfigureJsonRequirement) =>
    http.put<never, { data: GoalJsonRequirement }>(`/goals/${encodeURIComponent(goalId)}/json-acceptance/requirements/${encodeURIComponent(key)}`, data),
}
