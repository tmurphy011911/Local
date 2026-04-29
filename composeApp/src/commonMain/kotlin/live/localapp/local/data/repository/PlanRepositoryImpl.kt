package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanAttendee
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.domain.model.PlanTravel
import live.localapp.local.domain.model.ReservationState
import live.localapp.local.domain.model.TicketState
import live.localapp.local.domain.repository.PlanRepository
import live.localapp.local.domain.repository.UserPreferencesRepository

// ── Supabase DTOs ──

@Serializable
private data class PlanDto(
    val id: String = "",
    @SerialName("user_id") val userId: String,
    val name: String,
    val date: String? = null,
    val city: String = "Boston",
    val days: List<PlanDayDto> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("is_past") val isPast: Boolean = false,
    @SerialName("group_size") val groupSize: Int? = null,
    @SerialName("attendees_can_edit") val attendeesCanEdit: Boolean = false,
)

@Serializable
private data class PlanDayDto(
    val label: String,
    @SerialName("short_name") val shortName: String,
    val slots: List<PlanSlotDto> = emptyList(),
    val travel: List<PlanTravelDto> = emptyList(),
)

@Serializable
private data class PlanSlotDto(
    val id: String,
    val time: String,
    val emoji: String = "",
    val name: String,
    val detail: String = "",
    val price: String = "",
    @SerialName("is_free") val isFree: Boolean = false,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("is_custom") val isCustom: Boolean = false,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("ticket_url") val ticketUrl: String? = null,
    @SerialName("venue_rating") val venueRating: Double? = null,
    @SerialName("venue_address") val venueAddress: String? = null,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("venue_lat") val venueLat: Double? = null,
    @SerialName("venue_lng") val venueLng: Double? = null,
    @SerialName("price_min") val priceMin: Double? = null,
    @SerialName("price_max") val priceMax: Double? = null,
    @SerialName("reservation_url") val reservationUrl: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("venue_website") val venueWebsite: String? = null,
    @SerialName("venue_category") val venueCategory: String? = null,
    // V1 reservation/ticket state machine — see PlanSlot for details.
    @SerialName("venue_reservable") val venueReservable: Boolean? = null,
    @SerialName("reservation_state") val reservationState: String = "none",
    @SerialName("reservation_party_size") val reservationPartySize: Int? = null,
    @SerialName("reservation_time") val reservationTime: String? = null,
    @SerialName("ticket_state") val ticketState: String = "none",
    @SerialName("ticket_count") val ticketCount: Int? = null,
)

@Serializable
private data class PlanTravelDto(
    val mode: String,
    val mins: Int,
)

// ── Insert DTO (no id — DB generates it) ──

@Serializable
private data class PlanInsertDto(
    @SerialName("user_id") val userId: String,
    val name: String,
    val date: String? = null,
    val city: String,
    val days: List<PlanDayDto>,
    @SerialName("group_size") val groupSize: Int? = null,
)

// ── Mappers ──

private fun PlanSlot.toDto() = PlanSlotDto(
    id = id, time = time, emoji = emoji, name = name, detail = detail, price = price,
    isFree = isFree, eventId = eventId, isCustom = isCustom, imageUrl = imageUrl,
    ticketUrl = ticketUrl, venueRating = venueRating, venueAddress = venueAddress,
    venueName = venueName,
    venueLat = venueLat, venueLng = venueLng, priceMin = priceMin, priceMax = priceMax,
    reservationUrl = reservationUrl, sourceUrl = sourceUrl, venueWebsite = venueWebsite, venueCategory = venueCategory,
    venueReservable = venueReservable,
    reservationState = reservationState.name.lowercase(),
    reservationPartySize = reservationPartySize,
    reservationTime = reservationTime,
    ticketState = ticketState.name.lowercase(),
    ticketCount = ticketCount,
)

private fun PlanDayDto.toDomain() = PlanDay(
    label = label,
    shortName = shortName,
    slots = slots.map { it.toDomain() },
    travel = travel.map { PlanTravel(it.mode, it.mins) },
)

private fun PlanSlotDto.toDomain() = PlanSlot(
    id = id,
    time = time,
    emoji = emoji,
    name = name,
    detail = detail,
    price = price,
    isFree = isFree,
    eventId = eventId,
    isCustom = isCustom,
    imageUrl = imageUrl,
    ticketUrl = ticketUrl,
    venueRating = venueRating,
    venueAddress = venueAddress,
    venueName = venueName,
    venueLat = venueLat,
    venueLng = venueLng,
    priceMin = priceMin,
    priceMax = priceMax,
    reservationUrl = reservationUrl,
    sourceUrl = sourceUrl,
    venueWebsite = venueWebsite,
    venueCategory = venueCategory,
    venueReservable = venueReservable,
    reservationState = parseReservationState(reservationState),
    reservationPartySize = reservationPartySize,
    reservationTime = reservationTime,
    ticketState = parseTicketState(ticketState),
    ticketCount = ticketCount,
)

private fun parseReservationState(s: String): ReservationState =
    when (s.lowercase()) {
        "made" -> ReservationState.MADE
        "unavailable" -> ReservationState.UNAVAILABLE
        else -> ReservationState.NONE
    }

private fun parseTicketState(s: String): TicketState =
    when (s.lowercase()) {
        "bought" -> TicketState.BOUGHT
        "unavailable" -> TicketState.UNAVAILABLE
        else -> TicketState.NONE
    }

private fun PlanDto.toDomain(creatorFirstName: String? = null): Plan {
    // Strip trailing "phantom" days — empty days with a fallback shortName
    // like "Day 2" (no real weekday) that crept in via earlier ingestion
    // bugs. Without this, single-day plans render as "TUE – Day 2 / 2 days"
    // on the Plans tab hero because buildDateDisplay joins first + last
    // shortName, and the day count uses the raw days.size.
    val trimmedDays = days.dropLastWhile { day ->
        day.slots.isEmpty() && (day.shortName.isBlank() ||
            day.shortName.startsWith("Day ", ignoreCase = true) ||
            day.shortName.all { it.isDigit() })
    }.ifEmpty { days } // never strip ALL days — keep at least one
    val dateDisplay = buildDateDisplay(trimmedDays)
    val creatorInitial = creatorFirstName?.firstOrNull()?.uppercase() ?: "Y"
    return Plan(
        id = id,
        title = name,
        city = city,
        days = trimmedDays.map { it.toDomain() },
        attendees = listOf(
            PlanAttendee(
                id = userId,
                name = "You",
                initials = creatorInitial,
                confirmed = true,
                avatarColor = 0xFFFF6B6B,
            ),
        ),
        createdAt = createdAt,
        dateDisplay = dateDisplay,
        isPast = isPast,
        creatorId = userId,
        groupSize = groupSize,
        attendeesCanEdit = attendeesCanEdit,
    )
}

private fun buildDateDisplay(days: List<PlanDayDto>): String {
    if (days.isEmpty()) return ""
    if (days.size == 1) return days.first().label
    val first = days.first().shortName
    val last = days.last().shortName
    return "$first – $last"
}

// ── Repository ──

class PlanRepositoryImpl(
    private val supabaseClient: SupabaseClient,
    private val userPrefsRepo: UserPreferencesRepository,
) : PlanRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    override suspend fun getPlans(): List<Plan> {
        val userId = userPrefsRepo.getLocalUserId()
        println("PlanRepository.getPlans: userId=$userId")
        if (userId == null) return emptyList()
        return try {
            val result = supabaseClient.from("plans")
                .select()  {
                    filter {
                        eq("user_id", userId)
                    }
                    order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                }
            val raw = result.data
            println("PlanRepository.getPlans: raw=${raw.take(500)}")
            val dtos = result.decodeList<PlanDto>()
            println("PlanRepository.getPlans: decoded ${dtos.size} plans")
            val firstName = userPrefsRepo.getLocalFirstName()
            val plansBare = dtos.map { it.toDomain(firstName) }
            // One batched query into plan_attendees to hydrate the real
            // attendee list for every plan, instead of the dummy "You-only"
            // list PlanDto.toDomain() returns. Counts + the View Attendees
            // sheet stay in sync with actual RSVPs from this point on.
            val attendeesByPlan = loadAttendeesBatch(plansBare.map { it.id })
            val plans = plansBare.map { plan ->
                val real = attendeesByPlan[plan.id]
                val confirmed = real?.count { it.confirmed } ?: 1
                plan.copy(
                    attendees = real?.takeIf { it.isNotEmpty() } ?: plan.attendees,
                    confirmedCount = confirmed,
                )
            }
            plans
        } catch (e: Throwable) {
            println("PlanRepository.getPlans error: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun savePlan(plan: Plan): String? {
        val userId = userPrefsRepo.getLocalUserId()
        println("PlanRepository.savePlan: userId=$userId title=${plan.title}")
        if (userId == null) return null
        try {
            val dayDtos = plan.days.map { day ->
                PlanDayDto(
                    label = day.label,
                    shortName = day.shortName,
                    slots = day.slots.map { it.toDto() },
                    travel = day.travel.map { PlanTravelDto(it.mode, it.mins) },
                )
            }

            // Extract date from first day label (e.g. "Saturday, March 28" → "2026-03-28")
            val planDate = try {
                val todayStr = kotlin.time.Clock.System.now().toString().take(10)
                todayStr // Use today as fallback — the day labels are display-only
            } catch (_: Throwable) { null }

            val insertDto = PlanInsertDto(
                userId = userId,
                name = plan.title,
                date = planDate,
                city = plan.city,
                days = dayDtos,
                groupSize = plan.groupSize,
            )

            println("PlanRepository.savePlan: inserting with ${dayDtos.size} days, ${dayDtos.sumOf { it.slots.size }} slots")

            // Use raw JSON insert to see exact payload and error
            val jsonPayload = json.encodeToString(
                kotlinx.serialization.serializer<PlanInsertDto>(),
                insertDto,
            )
            println("PlanRepository.savePlan: payload=${jsonPayload.take(1500)}")

            // Use raw JSON insert to ensure null fields like source_url, venue_website are preserved
            val jsonElement = json.parseToJsonElement(jsonPayload)
            val result = supabaseClient.from("plans").insert(jsonElement) { select() }
                .decodeSingleOrNull<PlanDto>()
            println("PlanRepository.savePlan: SUCCESS '${plan.title}' id=${result?.id}")

            // Insert creator as confirmed attendee
            if (result?.id != null) {
                try { insertCreatorAttendee(result.id) } catch (_: Throwable) {}
            }
            return result?.id
        } catch (e: Throwable) {
            println("PlanRepository.savePlan error: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    override suspend fun updatePlan(plan: Plan) {
        if (plan.id.isBlank()) return
        try {
            val dayDtos = plan.days.map { day ->
                PlanDayDto(
                    label = day.label,
                    shortName = day.shortName,
                    slots = day.slots.map { it.toDto() },
                    travel = day.travel.map { PlanTravelDto(it.mode, it.mins) },
                )
            }
            supabaseClient.from("plans").update({
                set("days", json.encodeToJsonElement(
                    kotlinx.serialization.serializer<List<PlanDayDto>>(), dayDtos
                ))
                set("name", plan.title)
            }) {
                filter { eq("id", plan.id) }
            }
            println("PlanRepository.updatePlan: SUCCESS '${plan.title}'")
        } catch (e: Throwable) {
            println("PlanRepository.updatePlan error: ${e::class.simpleName}: ${e.message}")
        }
    }

    override suspend fun deletePlan(planId: String) {
        try {
            supabaseClient.from("plans").delete {
                filter { eq("id", planId) }
            }
        } catch (e: Throwable) {
            println("PlanRepository.deletePlan error: ${e.message}")
        }
    }

    override suspend fun getShareToken(planId: String): String? {
        return try {
            val params = kotlinx.serialization.json.buildJsonObject {
                put("p_plan_id", kotlinx.serialization.json.JsonPrimitive(planId))
            }
            val result = supabaseClient.postgrest.rpc("generate_share_token", params)
            val raw = result.data.trim('"')
            println("PlanRepository.getShareToken: token=$raw for planId=$planId")
            raw.ifBlank { null }
        } catch (e: Throwable) {
            println("PlanRepository.getShareToken error: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    override suspend fun acceptPlanInvite(shareToken: String): live.localapp.local.domain.repository.AcceptInviteResult {
        val userId = userPrefsRepo.getLocalUserId()
            ?: return live.localapp.local.domain.repository.AcceptInviteResult(false, "not_logged_in")
        return try {
            val params = kotlinx.serialization.json.buildJsonObject {
                put("p_share_token", kotlinx.serialization.json.JsonPrimitive(shareToken))
                put("p_user_id", kotlinx.serialization.json.JsonPrimitive(userId))
            }
            val result = supabaseClient.postgrest.rpc("accept_plan_invite", params)
            val json = kotlinx.serialization.json.Json.parseToJsonElement(result.data).jsonObject
            val error = json["error"]?.jsonPrimitive?.contentOrNull
            if (error != null) {
                println("PlanRepository.acceptPlanInvite: error=$error")
                live.localapp.local.domain.repository.AcceptInviteResult(false, error)
            } else {
                val acceptCount = json["accept_count"]?.jsonPrimitive?.intOrNull ?: 0
                val points = json["points_awarded"]?.jsonPrimitive?.intOrNull ?: 0
                println("PlanRepository.acceptPlanInvite: success, acceptCount=$acceptCount, points=$points")
                // Also insert into plan_attendees table
                val planId = json["plan_id"]?.jsonPrimitive?.contentOrNull
                if (planId != null) {
                    try {
                        supabaseClient.from("plan_attendees").insert(
                            mapOf("plan_id" to planId, "user_id" to userId, "confirmed" to true, "confirmed_at" to kotlin.time.Clock.System.now().toString()),
                        )
                    } catch (_: Throwable) { /* UNIQUE violation OK — already inserted */ }
                }
                live.localapp.local.domain.repository.AcceptInviteResult(true, null, acceptCount, points)
            }
        } catch (e: Throwable) {
            println("PlanRepository.acceptPlanInvite error: ${e::class.simpleName}: ${e.message}")
            live.localapp.local.domain.repository.AcceptInviteResult(false, e.message)
        }
    }

    override suspend fun getSharedPlan(shareToken: String): Plan? {
        return try {
            val result = supabaseClient.from("plans")
                .select {
                    filter { eq("share_token", shareToken) }
                }
            val dtos = result.decodeList<PlanDto>()
            println("PlanRepository.getSharedPlan: token=$shareToken found=${dtos.size}")
            val dto = dtos.firstOrNull() ?: return null
            val plan = dto.toDomain()

            // Look up creator name from user_profiles
            val creatorName = try {
                val profileResult = supabaseClient.from("user_profiles")
                    .select(columns = io.github.jan.supabase.postgrest.query.Columns.list("first_name")) {
                        filter { eq("id", dto.userId) }
                    }
                @Serializable
                data class NameDto(@SerialName("first_name") val firstName: String? = null)
                val names = profileResult.decodeList<NameDto>()
                names.firstOrNull()?.firstName
            } catch (_: Throwable) { null }

            plan.copy(creatorName = creatorName, shareToken = shareToken)
        } catch (e: Throwable) {
            println("PlanRepository.getSharedPlan error: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    override suspend fun updateGroupSize(planId: String, groupSize: Int) {
        try {
            supabaseClient.from("plans").update(
                mapOf("group_size" to groupSize),
            ) { filter { eq("id", planId) } }
        } catch (e: Throwable) {
            println("PlanRepository.updateGroupSize error: ${e.message}")
        }
    }

    override suspend fun updatePlanName(planId: String, newName: String) {
        try {
            supabaseClient.from("plans").update(
                mapOf("name" to newName),
            ) { filter { eq("id", planId) } }
        } catch (e: Throwable) {
            println("PlanRepository.updatePlanName error: ${e.message}")
        }
    }

    override suspend fun updateAttendeesCanEdit(planId: String, canEdit: Boolean) {
        try {
            supabaseClient.from("plans").update(
                mapOf("attendees_can_edit" to canEdit),
            ) { filter { eq("id", planId) } }
        } catch (e: Throwable) {
            println("PlanRepository.updateAttendeesCanEdit error: ${e.message}")
        }
    }

    @Serializable
    private data class AttendeeCountRow(val count: Int = 0)

    override suspend fun getConfirmedCount(planId: String): Int {
        return try {
            supabaseClient.from("plan_attendees")
                .select {
                    filter {
                        eq("plan_id", planId)
                        eq("confirmed", true)
                    }
                }
                .decodeList<Map<String, kotlinx.serialization.json.JsonElement>>()
                .size
        } catch (_: Throwable) { 1 } // Default to 1 (creator)
    }

    override suspend fun insertCreatorAttendee(planId: String) {
        val userId = userPrefsRepo.getLocalUserId() ?: return
        try {
            supabaseClient.from("plan_attendees").insert(
                mapOf(
                    "plan_id" to planId,
                    "user_id" to userId,
                    "name" to "You",
                    "is_creator" to true,
                    "confirmed" to true,
                ),
            )
        } catch (_: Throwable) {
            // Likely UNIQUE violation if already exists — that's fine
        }
    }

    @Serializable
    private data class PlanAttendeeRow(
        @SerialName("plan_id") val planId: String,
        @SerialName("user_id") val userId: String? = null,
        val name: String? = null,
        @SerialName("is_creator") val isCreator: Boolean = false,
        val confirmed: Boolean = false,
    )

    private fun PlanAttendeeRow.toDomain(currentUserFirstName: String? = null): live.localapp.local.domain.model.PlanAttendee {
        val displayName = name?.takeIf { it.isNotBlank() } ?: "Guest"
        val rowInitials = displayName.split(" ", "-")
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifBlank { "?" }
        // For the creator row, the DB stores name="You" — that yields "Y" as
        // initials. If the user has set a first name on their profile, prefer
        // its first letter instead (e.g. "T" for Tom). Falls back to "Y" if
        // no name is set.
        val initials = if (isCreator) {
            currentUserFirstName?.firstOrNull()?.uppercase() ?: "Y"
        } else {
            rowInitials
        }
        // Color buckets — pseudo-randomized by hashing the user id so each
        // attendee gets a stable color across renders.
        val palette = longArrayOf(0xFFFF6B6B, 0xFFAB83A1, 0xFF4FB3A0, 0xFFE8A24E, 0xFF6B8FD4, 0xFFA56BD4)
        val hash = (userId ?: displayName).hashCode()
        val color = palette[((hash % palette.size) + palette.size) % palette.size]
        return live.localapp.local.domain.model.PlanAttendee(
            id = userId ?: "guest_${hash.toString(16)}",
            name = if (isCreator) "You" else displayName,
            initials = initials,
            confirmed = confirmed,
            avatarColor = color,
        )
    }

    override suspend fun loadAttendees(planId: String): List<live.localapp.local.domain.model.PlanAttendee> {
        return try {
            val firstName = userPrefsRepo.getLocalFirstName()
            supabaseClient.from("plan_attendees")
                .select { filter { eq("plan_id", planId) } }
                .decodeList<PlanAttendeeRow>()
                .map { it.toDomain(firstName) }
        } catch (e: Throwable) {
            println("PlanRepository.loadAttendees($planId) failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadAttendeesBatch(
        planIds: List<String>,
    ): Map<String, List<live.localapp.local.domain.model.PlanAttendee>> {
        if (planIds.isEmpty()) return emptyMap()
        return try {
            val firstName = userPrefsRepo.getLocalFirstName()
            val rows = supabaseClient.from("plan_attendees")
                .select { filter { isIn("plan_id", planIds) } }
                .decodeList<PlanAttendeeRow>()
            rows.groupBy { it.planId }
                .mapValues { (_, list) -> list.map { it.toDomain(firstName) } }
        } catch (e: Throwable) {
            println("PlanRepository.loadAttendeesBatch(${planIds.size}) failed: ${e.message}")
            emptyMap()
        }
    }
}
