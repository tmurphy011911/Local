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
    val venueLat: Double? = null,
    val venueLng: Double? = null,
    val priceMin: Double? = null,
    val priceMax: Double? = null,
    val reservationUrl: String? = null,
    val sourceUrl: String? = null,
    val venueWebsite: String? = null,
    val venueCategory: String? = null,
    val isReserved: Boolean = false,
)

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
