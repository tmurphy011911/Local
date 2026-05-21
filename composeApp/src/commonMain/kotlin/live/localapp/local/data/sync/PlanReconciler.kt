package live.localapp.local.data.sync

import live.localapp.local.domain.model.Plan

/**
 * Pure reconciliation: given the latest server snapshot and the pending op
 * log, produces the plans the user should see locally.
 *
 * Rules:
 *  - Plans with a pending [PlanMutation.DeletePlan] are dropped — they're
 *    gone from the user's perspective even if the server hasn't ack'd yet.
 *  - For every other plan, the pending ops are replayed in oldest-first
 *    order on top of the server snapshot.
 *  - Ops that refer to a plan id missing from the server snapshot are
 *    ignored (the row may have been GC'd, or the user logged out).
 */
object PlanReconciler {

    fun reconcile(
        serverPlans: List<Plan>,
        pending: List<MutationRecord>,
    ): List<Plan> {
        if (pending.isEmpty()) return serverPlans

        val deletedPlanIds = pending
            .filter { it.mutation is PlanMutation.DeletePlan }
            .map { it.mutation.planId }
            .toSet()

        val pendingByPlan: Map<String, List<MutationRecord>> = pending
            .filter { it.mutation !is PlanMutation.DeletePlan }
            .groupBy { it.mutation.planId }

        return serverPlans
            .filter { it.id !in deletedPlanIds }
            .map { plan ->
                val ops = pendingByPlan[plan.id] ?: return@map plan
                ops.fold(plan) { acc, record -> record.mutation.applyTo(acc) }
            }
    }
}
