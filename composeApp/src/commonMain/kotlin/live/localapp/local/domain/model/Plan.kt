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
)

data class PlanDay(
    val label: String,       // "Friday, Nov 8"
    val shortName: String,   // "Fri"
    val slots: List<PlanSlot>,
    val travel: List<PlanTravel> = emptyList(),
)

data class PlanSlot(
    val id: String,
    val time: String,        // "7pm"
    val emoji: String,
    val name: String,
    val detail: String,      // venue name + context
    val price: String,
    val isFree: Boolean,
    val eventId: String? = null,
    val isCustom: Boolean = false,
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
