package live.localapp.local.domain.model

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
)

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
enum class ReservationState { NONE, MADE, UNAVAILABLE }

/** Ticket state machine — same shape as ReservationState but for ticketed events. */
enum class TicketState { NONE, BOUGHT, UNAVAILABLE }

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
    val imageUrl: String? = null,
    val ticketUrl: String? = null,
    val venueRating: Double? = null,
    val venueAddress: String? = null,
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
