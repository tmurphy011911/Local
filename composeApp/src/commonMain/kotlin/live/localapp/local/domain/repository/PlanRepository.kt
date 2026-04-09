package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Plan

interface PlanRepository {
    suspend fun getPlans(): List<Plan>
    suspend fun savePlan(plan: Plan)
    suspend fun updatePlan(plan: Plan)
    suspend fun deletePlan(planId: String)
    suspend fun getShareToken(planId: String): String?
    suspend fun getSharedPlan(shareToken: String): Plan?
    suspend fun acceptPlanInvite(shareToken: String): AcceptInviteResult
}

data class AcceptInviteResult(
    val success: Boolean,
    val error: String? = null,
    val acceptCount: Int = 0,
    val pointsAwarded: Int = 0,
)
