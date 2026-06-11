package live.localapp.local.data.sync

import kotlinx.serialization.Serializable
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.domain.model.ReservationState
import live.localapp.local.domain.model.TicketState

/**
 * One step of intent against a saved [Plan]. Persisted to the local mutation
 * log, replayed by [PlanReconciler] when reconciling server reads, and
 * (Phase 2) translated into atomic Postgres RPCs by the syncer.
 *
 * `applyTo` is intentionally tolerant — if the target slot/day no longer
 * exists (because a previous mutation already removed it, or the plan was
 * pulled fresh from the server), the mutation returns `plan` unchanged. The
 * server is the authority on whether the op also became a no-op there.
 */
@Serializable
sealed class PlanMutation {
    abstract val opId: String
    abstract val planId: String
    abstract val createdAt: Long

    /** Locally apply this mutation. Returns the modified plan, or the plan
     *  unchanged when the target row no longer matches (replay-safe). */
    abstract fun applyTo(plan: Plan): Plan

    // ── Slot-level ──────────────────────────────────────────────────────

    @Serializable
    data class RemoveSlot(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val slotId: String,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.mapDay(dayIndex) { day ->
            day.copy(slots = day.slots.filter { it.id != slotId })
        }
    }

    @Serializable
    data class AddSlot(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val slot: PlanSlot,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.mapDay(dayIndex) { day ->
            // Idempotent — replaying the same op twice doesn't add a duplicate.
            if (day.slots.any { it.id == slot.id }) day
            // Re-sort after insert so a slot added out of order (e.g. a 7 AM
            // event added when the day already has a 10 AM stop) lands in its
            // correct chronological position — matching SwapSlot and
            // SetReservationDetails, which already re-sort.
            else day.copy(slots = (day.slots + slot).sortedBy { parseSlotTimeMinutes(it.time) })
        }
    }

    @Serializable
    data class SwapSlot(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val oldSlotId: String,
        val newSlot: PlanSlot,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.mapDay(dayIndex) { day ->
            val replaced = day.slots.map { existing ->
                if (existing.id == oldSlotId) newSlot.copy(
                    id = newSlot.id.ifBlank { existing.id },
                ) else existing
            }
            day.copy(slots = replaced.sortedBy { parseSlotTimeMinutes(it.time) })
        }
    }

    @Serializable
    data class SetReservationDetails(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val slotId: String,
        val partySize: Int,
        val reservationTime: String,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.mapDay(dayIndex) { day ->
            val updated = day.slots.map { s ->
                if (s.id != slotId) s else {
                    val newTime = if (reservationTime.isNotBlank() && reservationTime != s.time) reservationTime else s.time
                    s.copy(
                        reservationState = ReservationState.MADE,
                        reservationPartySize = partySize,
                        reservationTime = reservationTime,
                        ticketState = TicketState.NONE,
                        ticketCount = null,
                        time = newTime,
                    )
                }
            }
            // Reservation time changes can shift the slot to a new chronological
            // position; re-sort the day so the timeline stays in order.
            day.copy(slots = updated.sortedBy { parseSlotTimeMinutes(it.time) })
        }
    }

    @Serializable
    data class SetReservationUnavailable(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val slotId: String,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.mapSlot(dayIndex, slotId) { s ->
            s.copy(
                reservationState = ReservationState.UNAVAILABLE,
                reservationPartySize = null,
                reservationTime = null,
            )
        }
    }

    @Serializable
    data class ClearReservation(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val slotId: String,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.mapSlot(dayIndex, slotId) { s ->
            s.copy(
                reservationState = ReservationState.NONE,
                reservationPartySize = null,
                reservationTime = null,
            )
        }
    }

    @Serializable
    data class SetTicketDetails(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val slotId: String,
        val ticketCount: Int,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.mapSlot(dayIndex, slotId) { s ->
            s.copy(
                ticketState = TicketState.BOUGHT,
                ticketCount = ticketCount,
                reservationState = ReservationState.NONE,
                reservationPartySize = null,
                reservationTime = null,
            )
        }
    }

    @Serializable
    data class SetTicketsUnavailable(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val slotId: String,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.mapSlot(dayIndex, slotId) { s ->
            s.copy(ticketState = TicketState.UNAVAILABLE, ticketCount = null)
        }
    }

    @Serializable
    data class ClearTickets(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val slotId: String,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.mapSlot(dayIndex, slotId) { s ->
            s.copy(ticketState = TicketState.NONE, ticketCount = null)
        }
    }

    /** Legacy binary reservation toggle. Still used by the per-stop overflow. */
    @Serializable
    data class SetSlotReserved(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val slotId: String,
        val reserved: Boolean,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.mapSlot(dayIndex, slotId) { s ->
            s.copy(
                reservationState = if (reserved) ReservationState.MADE else ReservationState.NONE,
            )
        }
    }

    // ── Day-level ───────────────────────────────────────────────────────

    @Serializable
    data class AddDay(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val day: PlanDay,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan {
            // Idempotent on identical labels — protects against double-replay.
            if (plan.days.any { it.label == day.label && it.slots.size == day.slots.size }) return plan
            return plan.copy(days = plan.days + day)
        }
    }

    @Serializable
    data class RemoveDay(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan {
            if (dayIndex !in plan.days.indices) return plan
            return plan.copy(days = plan.days.filterIndexed { i, _ -> i != dayIndex })
        }
    }

    /** Wholesale day replacement — used by Bo's `update_plan` confirmation flow. */
    @Serializable
    data class ReplaceDay(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val dayIndex: Int,
        val day: PlanDay,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan {
            val days = plan.days.toMutableList()
            if (dayIndex in days.indices) days[dayIndex] = day else days.add(day)
            return plan.copy(days = days)
        }
    }

    // ── Plan-level ──────────────────────────────────────────────────────

    @Serializable
    data class SetPlanName(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val name: String,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.copy(title = name)
    }

    @Serializable
    data class SetGroupSize(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val size: Int,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.copy(groupSize = size)
    }

    @Serializable
    data class SetAttendeesCanEdit(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
        val canEdit: Boolean,
    ) : PlanMutation() {
        override fun applyTo(plan: Plan): Plan = plan.copy(attendeesCanEdit = canEdit)
    }

    /**
     * Tombstone the plan locally. Reconciler drops a plan with a pending
     * DeletePlan op so it doesn't reappear if the server fetch hasn't yet
     * reflected the delete.
     */
    @Serializable
    data class DeletePlan(
        override val opId: String,
        override val planId: String,
        override val createdAt: Long,
    ) : PlanMutation() {
        // Reconciler handles delete by filtering — applyTo is unused for this case.
        override fun applyTo(plan: Plan): Plan = plan
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

private fun Plan.mapDay(dayIndex: Int, transform: (PlanDay) -> PlanDay): Plan {
    if (dayIndex !in days.indices) return this
    val updated = days.mapIndexed { i, d -> if (i == dayIndex) transform(d) else d }
    return copy(days = updated)
}

private fun Plan.mapSlot(dayIndex: Int, slotId: String, transform: (PlanSlot) -> PlanSlot): Plan =
    mapDay(dayIndex) { day ->
        day.copy(slots = day.slots.map { if (it.id == slotId) transform(it) else it })
    }

/**
 * Parse a slot's `time` field (e.g. "7:00 PM", "8 PM", "19:00") into total
 * minutes since midnight so day-sort comparisons are chronological. String
 * comparison is wrong — "11:23 PM" sorts before "6:30 PM" alphabetically.
 * Returns Int.MAX_VALUE for unparseable strings so they sink to the end.
 */
internal fun parseSlotTimeMinutes(s: String): Int {
    if (s.isBlank()) return Int.MAX_VALUE
    val cleaned = s.trim().uppercase()
    val isPm = cleaned.contains("PM")
    val isAm = cleaned.contains("AM")
    val withoutMeridiem = cleaned
        .replace("PM", "")
        .replace("AM", "")
        .replace(".", "")
        .trim()
    val parts = withoutMeridiem.split(":")
    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return Int.MAX_VALUE
    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    val h24 = when {
        isPm && hour < 12 -> hour + 12
        isAm && hour == 12 -> 0
        else -> hour
    }
    return h24 * 60 + minute
}
