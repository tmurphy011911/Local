package live.localapp.local.data.plan

import kotlinx.serialization.Serializable
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanDay

/**
 * Persistent last-known-good store for plans, used as an offline fallback
 * cache (e.g., Bo hybrid project Sprint 0). Implementations encrypt at rest
 * (Android EncryptedSharedPreferences; iOS Keychain with
 * kSecAttrAccessibleWhenUnlockedThisDeviceOnly).
 *
 * Policy:
 *  - At most 20 entries (LRU by insertion timestamp; oldest evicted on save).
 *  - DTO-only serialization: no PII (no names, attendees, RSVPs, locations,
 *    customPlanName, or any user-authored text). The `toPlan()` mapper
 *    re-hydrates a stub Plan with empty PII fields — callers MUST treat
 *    these as placeholders, not real values.
 *  - Cleared on sign-out.
 */
interface PlanLastKnownGoodStore {
    suspend fun save(plan: Plan)
    suspend fun load(planId: String): Plan?
    suspend fun loadAll(): List<Plan>
    suspend fun evict(planId: String)
    suspend fun clearAll()
}

/**
 * The ONLY thing that ever gets serialized. Intentionally narrow: no PII,
 * no user-authored strings, no attendee/RSVP data. If you find yourself
 * adding a field here, ask whether it's PII first.
 */
@Serializable
internal data class PlanLkgDto(
    val id: String,
    /** ISO-8601 string representation of the plan's primary time. */
    val time: String,
    val slotCount: Int,
    val imageUrls: List<String>,
)

/** LRU policy: keep at most this many entries; evict oldest on save. */
internal const val PLAN_LKG_MAX_ENTRIES = 20

/**
 * Map a [Plan] to its LKG DTO. Only non-PII summary fields are retained.
 * `time` is sourced from the first non-blank slot time, or empty string if
 * none exist.
 */
internal fun Plan.toLkgDto(): PlanLkgDto {
    val firstSlot = days.firstOrNull()?.slots?.firstOrNull()
    val time = firstSlot?.time?.takeIf { it.isNotBlank() } ?: createdAt
    val slotCount = days.sumOf { it.slots.size }
    val imageUrls = days
        .flatMap { it.slots }
        .mapNotNull { it.imageUrl }
        .filter { it.isNotBlank() }
        .distinct()
    return PlanLkgDto(
        id = id,
        time = time,
        slotCount = slotCount,
        imageUrls = imageUrls,
    )
}

/**
 * Reconstruct a stub [Plan] from a stored DTO. PII fields are blank by
 * design — never reconstruct title, attendees, city, dateDisplay, creator
 * info, etc. from the cache. Callers should treat the result as a
 * skeletal placeholder, not a real plan.
 */
internal fun PlanLkgDto.toPlan(): Plan = Plan(
    id = id,
    title = "",
    city = "",
    days = emptyList<PlanDay>(),
    attendees = emptyList(),
    createdAt = time,
    dateDisplay = "",
    isPast = false,
    creatorId = null,
    creatorName = null,
    shareToken = null,
    groupSize = null,
    confirmedCount = 0,
    attendeesCanEdit = false,
)

/**
 * Platform-provided factory. Android constructs the EncryptedSharedPreferences
 * impl from the current Activity context; iOS constructs the Keychain impl.
 */
expect fun providePlanLastKnownGoodStore(): PlanLastKnownGoodStore
