package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanAttendee
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.domain.model.PlanTravel
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
    @SerialName("venue_lat") val venueLat: Double? = null,
    @SerialName("venue_lng") val venueLng: Double? = null,
    @SerialName("price_min") val priceMin: Double? = null,
    @SerialName("price_max") val priceMax: Double? = null,
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
)

// ── Mappers ──

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
    venueLat = venueLat,
    venueLng = venueLng,
    priceMin = priceMin,
    priceMax = priceMax,
)

private fun PlanDto.toDomain(): Plan {
    val dateDisplay = buildDateDisplay(days)
    return Plan(
        id = id,
        title = name,
        city = city,
        days = days.map { it.toDomain() },
        attendees = listOf(
            PlanAttendee(
                id = userId,
                name = "You",
                initials = "Y",
                confirmed = true,
                avatarColor = 0xFFFF6B6B,
            ),
        ),
        createdAt = createdAt,
        dateDisplay = dateDisplay,
        isPast = isPast,
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
            dtos.map { it.toDomain() }
        } catch (e: Throwable) {
            println("PlanRepository.getPlans error: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun savePlan(plan: Plan) {
        val userId = userPrefsRepo.getLocalUserId()
        println("PlanRepository.savePlan: userId=$userId title=${plan.title}")
        if (userId == null) return
        try {
            val dayDtos = plan.days.map { day ->
                PlanDayDto(
                    label = day.label,
                    shortName = day.shortName,
                    slots = day.slots.map { slot ->
                        PlanSlotDto(
                            id = slot.id,
                            time = slot.time,
                            emoji = slot.emoji,
                            name = slot.name,
                            detail = slot.detail,
                            price = slot.price,
                            isFree = slot.isFree,
                            eventId = slot.eventId,
                            isCustom = slot.isCustom,
                        )
                    },
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
            )

            println("PlanRepository.savePlan: inserting with ${dayDtos.size} days, ${dayDtos.sumOf { it.slots.size }} slots")

            // Use raw JSON insert to see exact payload and error
            val jsonPayload = json.encodeToString(
                kotlinx.serialization.serializer<PlanInsertDto>(),
                insertDto,
            )
            println("PlanRepository.savePlan: payload=${jsonPayload.take(500)}")

            supabaseClient.from("plans").insert(insertDto)
            println("PlanRepository.savePlan: SUCCESS '${plan.title}'")
        } catch (e: Throwable) {
            println("PlanRepository.savePlan error: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun updatePlan(plan: Plan) {
        if (plan.id.isBlank()) return
        try {
            val dayDtos = plan.days.map { day ->
                PlanDayDto(
                    label = day.label,
                    shortName = day.shortName,
                    slots = day.slots.map { slot ->
                        PlanSlotDto(
                            id = slot.id, time = slot.time, emoji = slot.emoji,
                            name = slot.name, detail = slot.detail, price = slot.price,
                            isFree = slot.isFree, eventId = slot.eventId, isCustom = slot.isCustom,
                        )
                    },
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
}
