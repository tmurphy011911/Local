package live.localapp.local.domain.service

import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.domain.model.Venue
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.VenueRepository
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Returns 3 swap alternatives for a Plan Detail slot per spec §3.4.
 *
 * V1 reuses the existing nearby-search repos for speed:
 * - Restaurants: same category, within ~1 mi, not already in plan, reservable preferred
 * - Ticketed events: same/adjacent category, within ~1 mi, ±60 min of original, not already in plan
 *
 * V1.5 can layer Claude ranking on top — for now we sort by a simple
 * `rating × proximity × time-fit` heuristic. Returns up to [maxCount]
 * candidates; empty list when nothing usable comes back.
 */
class PlanSwapService(
    private val venueRepo: VenueRepository,
    private val eventRepo: EventRepository,
) {

    /** A swap alternative — venue or event. Both shapes are flat for serialization. */
    data class SwapAlternative(
        val kind: Kind,
        val sourceId: String,                 // venue.id or event.id
        val name: String,
        val category: String?,                // raw category, used for the subtitle
        val neighborhood: String?,             // first part of the address
        val rating: Double?,
        val imageUrl: String?,
        val lat: Double?,
        val lng: Double?,
        val ticketUrl: String? = null,
        val sourceUrl: String? = null,
        val reservationUrl: String? = null,
        val venueWebsite: String? = null,
        val venueAddress: String? = null,
        val priceMin: Double? = null,
        val priceMax: Double? = null,
        val isFree: Boolean = false,
        // Event-only
        val eventTitle: String? = null,
        val eventStartAt: String? = null,
        val eventTime: String? = null,        // formatted "h:mm a" for slot.time field
    ) {
        enum class Kind { RESTAURANT, EVENT }
    }

    suspend fun fetchAlternatives(
        slot: PlanSlot,
        plan: Plan,
        maxCount: Int = 3,
    ): List<SwapAlternative> {
        return if (slot.eventId == null && isRestaurantSlot(slot)) {
            fetchRestaurantAlternatives(slot, plan, maxCount)
        } else if (slot.eventId != null) {
            fetchEventAlternatives(slot, plan, maxCount)
        } else {
            emptyList()
        }
    }

    // ── Restaurants ────────────────────────────────────────────────────

    private suspend fun fetchRestaurantAlternatives(
        slot: PlanSlot,
        plan: Plan,
        maxCount: Int,
    ): List<SwapAlternative> {
        val excludedNames = plan.days.flatMap { d -> d.slots.map { it.name.lowercase() } }.toSet()
        val origLat = slot.venueLat
        val origLng = slot.venueLng
        val originalAddress = slot.venueAddress ?: ""
        val neighborhood = originalAddress.substringBefore(",").trim()

        val candidates: List<Venue> = try {
            venueRepo.searchVenues(
                location = neighborhood.ifBlank { null },
                category = slot.venueCategory,
                limit = 30,
            )
        } catch (_: Throwable) { emptyList() }

        return candidates
            .asSequence()
            .filter { v -> v.name.lowercase() !in excludedNames }
            .filter { v -> origLat == null || origLng == null || v.latitude == null || v.longitude == null ||
                miles(origLat, origLng, v.latitude!!, v.longitude!!) <= 1.0 }
            .sortedBy { v ->
                val dist = if (origLat != null && origLng != null && v.latitude != null && v.longitude != null)
                    miles(origLat, origLng, v.latitude!!, v.longitude!!) else 5.0
                val rating = v.googleRating ?: 0.0
                // Sort key — lower is better. Smaller distance + higher rating wins.
                dist - (rating * 0.2)
            }
            .take(maxCount)
            .map { v ->
                SwapAlternative(
                    kind = SwapAlternative.Kind.RESTAURANT,
                    sourceId = v.id,
                    name = v.name,
                    category = v.category,
                    neighborhood = v.address?.substringBefore(",")?.trim(),
                    rating = v.googleRating,
                    imageUrl = v.photoUrl ?: v.imageUrl,
                    lat = v.latitude,
                    lng = v.longitude,
                    venueAddress = v.address,
                    venueWebsite = v.website,
                    reservationUrl = v.reservationUrl,
                )
            }
            .toList()
    }

    // ── Events ─────────────────────────────────────────────────────────

    private suspend fun fetchEventAlternatives(
        slot: PlanSlot,
        plan: Plan,
        maxCount: Int,
    ): List<SwapAlternative> {
        val excludedIds = plan.days.flatMap { d -> d.slots.mapNotNull { it.eventId } }.toSet()
        val origLat = slot.venueLat
        val origLng = slot.venueLng
        val origStartAt = parseHourMin(slot.time)

        val candidates: List<Event> = try {
            eventRepo.searchEvents(
                category = slot.venueCategory,
                userLat = origLat,
                userLng = origLng,
                radiusMiles = 1.0,
                limit = 40,
            )
        } catch (_: Throwable) { emptyList() }

        return candidates
            .asSequence()
            .filter { e -> e.id !in excludedIds }
            .filter { e -> e.ticketUrl != null }
            .filter { e ->
                val candStart = e.startAt?.let { parseHourMin(extractTimeFromStartAt(it)) }
                origStartAt == null || candStart == null ||
                    abs(diffMinutes(origStartAt, candStart)) <= 60
            }
            .sortedBy { e ->
                val dist = if (origLat != null && origLng != null && e.venueLat != null && e.venueLng != null)
                    miles(origLat, origLng, e.venueLat, e.venueLng) else 5.0
                val rating = 0.0 // events don't have ratings; venue rating could be joined later
                dist - (rating * 0.2)
            }
            .take(maxCount)
            .map { e ->
                SwapAlternative(
                    kind = SwapAlternative.Kind.EVENT,
                    sourceId = e.id,
                    name = e.title,
                    category = e.subcategory ?: e.category,
                    neighborhood = e.venueAddress?.substringBefore(",")?.trim() ?: e.venueCity,
                    rating = null,
                    imageUrl = e.imageUrl,
                    lat = e.venueLat,
                    lng = e.venueLng,
                    ticketUrl = e.ticketUrl,
                    sourceUrl = e.sourceUrl,
                    venueAddress = e.venueAddress,
                    priceMin = e.priceMin,
                    priceMax = e.priceMax,
                    isFree = e.isFree,
                    eventTitle = e.title,
                    eventStartAt = e.startAt,
                    eventTime = e.startAt?.let { extractTimeFromStartAt(it) },
                )
            }
            .toList()
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun isRestaurantSlot(slot: PlanSlot): Boolean {
        val restaurantWords = listOf(
            "restaurant", "bar", "brewery", "winery", "cafe", "bistro",
            "pub", "tavern", "grill", "steakhouse", "pizzeria", "bakery", "diner",
        )
        val cat = slot.venueCategory?.lowercase() ?: ""
        val name = slot.name.lowercase()
        return restaurantWords.any { cat.contains(it) || name.contains(it) }
    }

    /** Approximate miles between two lat/lng pairs (good enough for ~1 mi filter). */
    private fun miles(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * 69.0
        val dLng = (lng2 - lng1) * 53.0 * cos(lat1 * (kotlin.math.PI / 180.0))
        return sqrt(dLat * dLat + dLng * dLng)
    }

    /** Parse "7:00 PM" / "19:00" into total minutes since midnight. */
    private fun parseHourMin(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        val cleaned = s.trim().replace(" ", " ").replace(" ", " ")
        val pmAm = cleaned.uppercase()
        val isPm = pmAm.contains("PM")
        val isAm = pmAm.contains("AM")
        val withoutMeridiem = cleaned.replace(Regex("\\s*[ap]m", RegexOption.IGNORE_CASE), "").trim()
        val parts = withoutMeridiem.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val h24 = when {
            isPm && hour < 12 -> hour + 12
            isAm && hour == 12 -> 0
            else -> hour
        }
        return h24 * 60 + min
    }

    private fun diffMinutes(a: Int, b: Int): Int = a - b

    private fun extractTimeFromStartAt(iso: String): String? {
        // ISO timestamp like "2026-05-03T19:00:00Z" → "7:00 PM"
        val t = iso.substringAfter("T", "").substringBefore("Z")
        val parts = t.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val h12 = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        val meridiem = if (h < 12) "AM" else "PM"
        return "$h12:${m.toString().padStart(2, '0')} $meridiem"
    }
}

