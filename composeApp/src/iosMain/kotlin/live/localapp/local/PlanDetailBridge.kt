package live.localapp.local

/**
 * Bridge between Kotlin (PlansViewModel) and the SwiftUI `PlanDetailView`.
 *
 * Swift sets the present/dismiss/pushPlanState handlers; Kotlin invokes them
 * when a plan card expands or when state mutates. Kotlin sets the action
 * handlers (onEditPlan, onDeletePlan, onSetSlotReserved, …); Swift invokes
 * them from menu/button taps.
 *
 * V1 caps mirrored on Swift side (see PlanDetailView.swift):
 *   - 7 days max per plan
 *   - 8 stops per day max
 *   - 500 attendees max
 */
object PlanDetailBridge {
    // ── Presentation (Swift -> Compose) ────────────────────────────────
    /** Swift sets this. Compose calls it to present the plan detail with a hero transition. */
    var showPlanDetail: ((
        planJson: String,
        cardX: Float, cardY: Float, cardW: Float, cardH: Float,
    ) -> Unit)? = null

    /** Swift sets this. Compose calls it to push updated plan JSON when state mutates. */
    var pushPlanState: ((String) -> Unit)? = null

    /** Swift sets this. Compose can request dismissal programmatically. */
    var dismissPlanDetail: (() -> Unit)? = null

    // ── Lifecycle (Compose -> Swift) ───────────────────────────────────
    var onDismissed: (() -> Unit)? = null

    // ── Header actions ─────────────────────────────────────────────────
    var onShare: (() -> Unit)? = null
    /** Shared-invite mode only: tapped "Save to My Plans" / "Accept". */
    var onSaveToPlan: (() -> Unit)? = null
    /** Tapped the Edit Plan item in the header overflow menu. */
    var onEditPlan: (() -> Unit)? = null
    /** Tapped Delete Plan and confirmed. */
    var onDeletePlan: (() -> Unit)? = null
    /** Saved a new plan name from the rename sheet. */
    var onChangeName: ((String) -> Unit)? = null

    // ── Day-level actions ──────────────────────────────────────────────
    /** Tapped + Add day. */
    var onAddDay: (() -> Unit)? = null
    /** Tapped + Add event on the active day → opens the native chooser
     *  (Add manually / Ask Bo). The "Ask Bo" branch invokes this. Param: dayIndex. */
    var onAddEvent: ((Int) -> Unit)? = null
    /** "Add manually" form saved. Builds a custom slot on the Kotlin side and
     *  pushes refreshed plan state back. Params:
     *  dayIndex, name, time (e.g. "7:30 PM"), locationName,
     *  address (nullable), lat (nullable), lng (nullable). */
    var onAddSlotManual: ((Int, String, String, String, String?, Double?, Double?) -> Unit)? = null
    /** Inline "Edit day" shortcut (asks Bo to change a specific day). Param: dayLabel. */
    var onEditDay: ((String) -> Unit)? = null

    // ── Slot-level actions ─────────────────────────────────────────────
    /** Tap on a slot card → open native event/venue detail. Params:
     *  dayIndex, slotId, eventId (nullable — null for venue stops),
     *  venueName (used for venue lookup when eventId is null). */
    var onSlotTap: ((Int, String, String?, String?) -> Unit)? = null
    /** Legacy binary flag — Phase 3 will replace with the state-machine actions below. */
    var onSetSlotReserved: ((Int, String, Boolean) -> Unit)? = null
    /** Save reservation details (Phase 2). Params: dayIndex, slotId, partySize, reservationTime (e.g. "7:00 PM"). */
    var onSaveReservationDetails: ((Int, String, Int, String) -> Unit)? = null
    /** Mark restaurant unavailable (State C). Params: dayIndex, slotId. */
    var onReservationUnavailable: ((Int, String) -> Unit)? = null
    /** Clear reservation — back to State A. Params: dayIndex, slotId. */
    var onClearReservation: ((Int, String) -> Unit)? = null
    /** Save ticket details (Phase 2). Params: dayIndex, slotId, ticketCount. */
    var onSaveTicketDetails: ((Int, String, Int) -> Unit)? = null
    /** Mark ticketed event unavailable (State C). Params: dayIndex, slotId. */
    var onTicketsUnavailable: ((Int, String) -> Unit)? = null
    /** Clear tickets — back to State A. Params: dayIndex, slotId. */
    var onClearTickets: ((Int, String) -> Unit)? = null
    /** "Replace this stop" — opens Bo with a replacement prompt. Params: dayIndex, slotId, venueName. */
    var onReplaceSlot: ((Int, String, String) -> Unit)? = null
    /** "Remove from plan" — removes a slot. Params: dayIndex, slotId. */
    var onRemoveSlot: ((Int, String) -> Unit)? = null

    // ── Swap Sheet (Phase 5) ───────────────────────────────────────────
    /** Swift requests 3 alternatives for a slot. Kotlin replies via [onSwapAlternativesReady].
     *  Params: dayIdx, slotId. */
    var onRequestSwapAlternatives: ((Int, String) -> Unit)? = null
    /** Kotlin -> Swift. JSON-encoded list of `SwapAlternative`. */
    var onSwapAlternativesReady: ((String) -> Unit)? = null
    /** Swap in an alternative. Params: dayIdx, oldSlotId, alternativeJson (single SwapAlternative). */
    var onSwapInAlternative: ((Int, String, String) -> Unit)? = null
    /** "See more options" — opens the per-plan Bo chat with a swap prompt. Params: dayIdx, slotId, venueName. */
    var onSeeMoreSwapOptions: ((Int, String, String) -> Unit)? = null
    /** "No reservations / tickets available" — opens Bo with a swap prompt naming the venue
     *  and the unavailability reason. Params: dayIdx, slotId, venueName, kind ("reservation" or "ticket"). */
    var onSwapForUnavailable: ((Int, String, String, String) -> Unit)? = null

    // ── Attendee actions ───────────────────────────────────────────────
    /** Tapped Invite. */
    var onInvite: (() -> Unit)? = null
    /** Set this user's RSVP. Param: status (one of: going, maybe, not_going, pending). */
    var onChangeRsvp: ((String) -> Unit)? = null
    /** Creator removed an invitee. Param: attendee userId. */
    var onRemoveAttendee: ((String) -> Unit)? = null
    /** Set the (display-only) "group size" cap. */
    var onSetGroupSize: ((Int) -> Unit)? = null
}
