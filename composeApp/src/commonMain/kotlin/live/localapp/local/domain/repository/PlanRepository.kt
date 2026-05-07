package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanAttendee

interface PlanRepository {
    suspend fun getPlans(): List<Plan>
    /** Fetch a single plan by id. Used by surfaces that hold a stale plan
     *  reference and need fresh DB state — e.g. PlanChatViewModel before
     *  handing the plan to Bo, so slot deletes/swaps from the Plan
     *  Detail screen are visible. */
    suspend fun getPlan(planId: String): Plan?
    /** Insert a new plan. Returns the inserted plan's server-side id, or null on failure. */
    suspend fun savePlan(plan: Plan): String?
    suspend fun updatePlan(plan: Plan)
    suspend fun deletePlan(planId: String)
    suspend fun getShareToken(planId: String): String?
    suspend fun getSharedPlan(shareToken: String): Plan?
    suspend fun acceptPlanInvite(shareToken: String): AcceptInviteResult
    suspend fun updateGroupSize(planId: String, groupSize: Int)
    suspend fun updatePlanName(planId: String, newName: String)
    suspend fun updateAttendeesCanEdit(planId: String, canEdit: Boolean)
    suspend fun getConfirmedCount(planId: String): Int
    suspend fun insertCreatorAttendee(planId: String)

    /**
     * Load all rows from `plan_attendees` for a single plan and map them
     * to domain [PlanAttendee] objects. Used to hydrate `Plan.attendees`
     * on plan-detail open so the "X unconfirmed" subtext + Attendee Sheet
     * reflect real RSVPs instead of just the creator stub.
     */
    suspend fun loadAttendees(planId: String): List<PlanAttendee>

    /**
     * Batch version — load attendees for many plans at once. Used by the
     * Plans tab to hydrate counts in a single query instead of N round
     * trips. Returns a map from planId → attendees.
     */
    suspend fun loadAttendeesBatch(planIds: List<String>): Map<String, List<PlanAttendee>>
}

data class AcceptInviteResult(
    val success: Boolean,
    val error: String? = null,
    val acceptCount: Int = 0,
    val pointsAwarded: Int = 0,
)
