package live.localapp.local.domain.model

import kotlinx.serialization.Serializable
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

data class Plan(
    val id: String,
    val title: String,
    val city: String,
    val days: List<PlanDay>,
    val attendees: List<PlanAttendee>,
    val createdAt: String,
    val dateDisplay: String,   // e.g. "Nov 8–10" or "This Saturday"
    val isPast: Boolean = false,
    val creatorId: String? = null,
    val creatorName: String? = null,
    val shareToken: String? = null,
    val groupSize: Int? = null,
    val confirmedCount: Int = 1,
    val attendeesCanEdit: Boolean = false,
    // Non-null when this plan is a copy the user saved by accepting someone
    // else's invite — holds the ORIGINAL creator's user id. Null for plans the
    // user originated. `creatorId` only tells you the owner (it maps from the
    // row's user_id), so it can't distinguish an accepted copy from a self-made
    // plan; this field is the authoritative "is this mine to share/manage" signal.
    val acceptedFromCreatorId: String? = null,
) {
    /** True when this plan was saved by accepting an invite (not originated here). */
    val isAcceptedCopy: Boolean get() = acceptedFromCreatorId != null
}

@Serializable
data class PlanDay(
    val label: String,       // "Friday, Nov 8"
    val shortName: String,   // "Fri"
    val slots: List<PlanSlot>,
    val travel: List<PlanTravel> = emptyList(),
)

/**
 * Reservation state machine per Plan Detail spec V1 (April 27, 2026).
 *  - NONE:        default, no reservation made
 *  - MADE:        creator has booked and saved party_size + time
 *  - UNAVAILABLE: creator marked the venue as unavailable; swap flow surfaces alternatives
 */
@Serializable
enum class ReservationState { NONE, MADE, UNAVAILABLE }

/** Ticket state machine — same shape as ReservationState but for ticketed events. */
@Serializable
enum class TicketState { NONE, BOUGHT, UNAVAILABLE }

@Serializable
data class PlanSlot(
    val id: String,
    val time: String,
    val emoji: String,
    val name: String,
    val detail: String,
    val price: String,
    val isFree: Boolean,
    val eventId: String? = null,
    val isCustom: Boolean = false,
    /**
     * True when Bo created this slot from a web_search finding (Phase 2).
     * The slot's [eventId] is the bo_web_events.id row, not events.id.
     */
    val isBoWeb: Boolean = false,
    /**
     * Phase 3.5: current state of the bo_web_events row this slot points at.
     * Hydrated when the plan loads; one of: unverified | verifying | verified
     * | rejected. Drives the slot's badge:
     *   unverified/verifying → coral "BO FOUND THIS — VERIFYING"
     *   verified             → no badge (promoted to events table)
     *   rejected             → red warning + "I've verified this" override
     * Null when isBoWeb=false (the field doesn't apply).
     */
    val boWebState: String? = null,
    val imageUrl: String? = null,
    val ticketUrl: String? = null,
    val venueRating: Double? = null,
    val venueAddress: String? = null,
    /** Venue city — populated from the event row's `venue_city` (events)
     *  or the venues table (venue stops). Used as a subtitle fallback
     *  when venueAddress is null/unparseable. */
    val venueCity: String? = null,
    val venueName: String? = null,
    val venueLat: Double? = null,
    val venueLng: Double? = null,
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val reservationUrl: String? = null,
    val sourceUrl: String? = null,
    val venueWebsite: String? = null,
    val venueCategory: String? = null,
    /**
     * Whether the underlying venue accepts/requires reservations. Joined from
     * `venues.reservable` at slot-creation time. Null when the slot is an event
     * (eventId != null) or when the venue isn't joined yet — UI defaults to
     * "treat as reservable" (i.e. show the Reservation chip) when null.
     */
    val venueReservable: Boolean? = null,
    /** Reservation state machine — see [ReservationState]. */
    val reservationState: ReservationState = ReservationState.NONE,
    /** Number of people the reservation is for (when state == MADE). */
    val reservationPartySize: Int? = null,
    /**
     * Reservation time as the user entered it in the Details Sheet. May differ
     * from [time] when the user picks a slightly different reservation time;
     * the time-update cascade syncs [time] to this and re-sorts the day.
     */
    val reservationTime: String? = null,
    /** Ticket state machine — see [TicketState]. */
    val ticketState: TicketState = TicketState.NONE,
    /** Number of tickets purchased (when state == BOUGHT). */
    val ticketCount: Int? = null,
) {
    /** Back-compat: derived from the reservation state machine. */
    val isReserved: Boolean get() = reservationState == ReservationState.MADE
}

@Serializable
data class PlanTravel(
    val mode: String,        // "walk" or "drive"
    val mins: Int,
)

data class PlanAttendee(
    val id: String,
    val name: String,
    val initials: String,
    val confirmed: Boolean,
    val avatarColor: Long,
)

// ── Past-plan detection ──────────────────────────────────────────────────
// Plans carry no machine-readable event date: `plans.date` is stamped with the
// creation date and the DB `is_past` flag is never maintained (always false).
// The only date signal is the day labels ("Friday, June 26" — month + day, no
// year). We infer the year from `createdAt`: plans are near-term, so the event's
// year is the creation year, bumped to the next year only when the label's
// month/day sits well before the creation date (the Dec→Jan wrap).

private val PLAN_MONTHS = listOf(
    "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december",
)

/** Parse a day label's month + day, e.g. "Friday, June 26" -> (6, 26). Null when
 *  the label carries no recognizable month + day (e.g. a "Day 1" placeholder). */
private fun parsePlanLabelMonthDay(label: String): Pair<Int, Int>? {
    val after = label.substringAfter(',', label).trim()
    val tokens = after.split(' ', ' ').filter { it.isNotBlank() }
    if (tokens.size < 2) return null
    val monthRaw = tokens[0].trim('.', ',', ' ').lowercase()
    val monthIdx = PLAN_MONTHS.indexOfFirst { it == monthRaw || it.take(3) == monthRaw.take(3) }
    if (monthIdx < 0) return null
    val day = tokens[1].takeWhile { it.isDigit() }.toIntOrNull() ?: return null
    if (day !in 1..31) return null
    return (monthIdx + 1) to day
}

/** The plan's last calendar day, inferred from its day labels + createdAt.
 *  Null when no day label is parseable. */
fun Plan.lastDayDate(): LocalDate? {
    val created = try { LocalDate.parse(createdAt.take(10)) } catch (_: Throwable) { null }
    val createdYear = created?.year ?: return null
    val createdEpoch = created.toEpochDays()
    return days.mapNotNull { d ->
        val (month, day) = parsePlanLabelMonthDay(d.label) ?: return@mapNotNull null
        val base = try { LocalDate(createdYear, month, day) } catch (_: Throwable) { return@mapNotNull null }
        // Label month/day well before creation → next-year event (Dec→Jan wrap).
        if (base.toEpochDays() < createdEpoch - 7)
            try { LocalDate(createdYear + 1, month, day) } catch (_: Throwable) { base }
        else base
    }.maxByOrNull { it.toEpochDays() }
}

/** True when the plan's last day is strictly before today. Unparseable dates and
 *  the stale DB flag are ORed in; parse failures fall back to "not past" so an
 *  ambiguous plan stays visible in the upcoming list rather than being hidden. */
fun Plan.isLastDayPast(): Boolean {
    if (isPast) return true
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val last = lastDayDate() ?: return false
    return last.toEpochDays() < today.toEpochDays()
}
