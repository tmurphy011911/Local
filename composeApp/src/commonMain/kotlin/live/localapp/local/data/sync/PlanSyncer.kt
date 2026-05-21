package live.localapp.local.data.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import live.localapp.local.domain.repository.PlanRepository

/**
 * Drains [MutationLog] in the background, applying each pending mutation
 * against Supabase via [PlanMutationRpc]. Single owner of all post-edit
 * network writes for the `plans` row — no other call site should call
 * `PlanRepository.updatePlan` directly once the syncer is in play.
 *
 * Each op is dispatched to a narrow Postgres RPC (`plan_remove_slot`,
 * `plan_add_slot`, `plan_replace_day`, etc.) that performs the read-
 * modify-write inside a single UPDATE. PostgreSQL's row-level lock
 * serializes concurrent ops, so multi-user / multi-device edits merge
 * naturally instead of clobbering each other.
 *
 * Ops process oldest-first per plan to preserve causality (an `AddSlot`
 * followed by a `SetReservationDetails` on the same id must execute in
 * that order).
 */
class PlanSyncer(
    private val log: MutationLog,
    private val planRepository: PlanRepository,
    private val rpc: PlanMutationRpc,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    init {
        scope.launch { runLoop() }
        // Kick once at startup so any persisted ops drain.
        scope.launch { wake.trySend(Unit) }
    }

    /** Enqueue a mutation and wake the drain loop. */
    suspend fun enqueue(mutation: PlanMutation) {
        log.enqueue(mutation)
        wake.trySend(Unit)
    }

    /** Force a drain attempt (used after auth, or by a manual retry UI). */
    fun kick() {
        wake.trySend(Unit)
    }

    /** Remove every pending op (used on logout). */
    suspend fun clear() {
        log.clear()
        _state.update { it.copy(stuckOpIds = emptySet(), lastError = null) }
    }

    private suspend fun runLoop() {
        while (true) {
            wake.receive()
            try {
                drainOnce()
            } catch (t: Throwable) {
                println("PlanSyncer: drain loop crashed (${t.message})")
                _state.update { it.copy(lastError = t.message) }
            }
            // Brief pause so a flurry of enqueues doesn't fan out into a
            // flurry of network round-trips.
            delay(150)
        }
    }

    private suspend fun drainOnce() {
        val records = log.pending.value.filter { it.attempts < MAX_ATTEMPTS }
        if (records.isEmpty()) {
            _state.update { it.copy(inFlight = false) }
            return
        }
        _state.update { it.copy(inFlight = true) }

        val byPlan: Map<String, List<MutationRecord>> = records
            .sortedBy { it.mutation.createdAt }
            .groupBy { it.mutation.planId }
        for ((planId, ops) in byPlan) {
            flushPlan(planId, ops)
        }

        // Surface anything that exhausted retries.
        val stuck = log.pending.value.filter { it.attempts >= MAX_ATTEMPTS }.map { it.opId }.toSet()
        _state.update { it.copy(inFlight = false, stuckOpIds = stuck) }
    }

    /**
     * Process every pending op for [planId] in order, dispatching each via
     * [PlanMutationRpc]. On a per-op failure we stop processing this plan's
     * queue: later ops may depend on the failed op having succeeded (e.g.
     * AddSlot + SetReservationDetails for the same id), and applying them
     * out of order would put the plan in a confused state. The retry loop
     * comes back later and tries again from the top.
     *
     * Special case: a pending [PlanMutation.DeletePlan] trumps everything —
     * we issue the delete and clear the queue for that plan.
     */
    private suspend fun flushPlan(planId: String, ops: List<MutationRecord>) {
        val deleteOp = ops.firstOrNull { it.mutation is PlanMutation.DeletePlan }
        if (deleteOp != null) {
            try {
                planRepository.deletePlan(planId)
                log.clearForPlan(planId)
            } catch (t: Throwable) {
                ops.forEach { log.markFailed(it.opId, t.message ?: "delete failed") }
                scheduleRetry()
            }
            return
        }

        for (record in ops) {
            try {
                rpc.apply(record.mutation)
                log.markSynced(record.opId)
            } catch (t: Throwable) {
                log.markFailed(record.opId, t.message ?: "rpc failed")
                scheduleRetry()
                return
            }
        }
    }

    private fun scheduleRetry() {
        // Simple linear backoff. We don't track per-op timers; the drain
        // loop wakes after [RETRY_DELAY_MS] and tries everything still
        // under the attempt cap.
        scope.launch {
            delay(RETRY_DELAY_MS)
            wake.trySend(Unit)
        }
    }

    data class SyncState(
        val inFlight: Boolean = false,
        val stuckOpIds: Set<String> = emptySet(),
        val lastError: String? = null,
    )

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val RETRY_DELAY_MS = 4_000L
    }
}
