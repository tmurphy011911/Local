package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Plan

interface PlanRepository {
    suspend fun getPlans(): List<Plan>
    suspend fun savePlan(plan: Plan)
    suspend fun deletePlan(planId: String)
}
