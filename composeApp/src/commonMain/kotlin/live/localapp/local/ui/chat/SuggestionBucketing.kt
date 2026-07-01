package live.localapp.local.ui.chat

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import live.localapp.local.domain.model.Event

/**
 * Shared day/category bucketing for Bo result trays — used by ChatViewModel
 * (to fill the tray from the full search pool, deterministically) AND by the
 * iOS ChatBridge regroup so both platforms label and order groups identically.
 *
 * Returns Triple(bucketKey, sortKey, displayLabel).
 */

/** Bucket an event by the local-time date of its `start_at`. */
fun computeEventDayKey(ev: Event, tz: TimeZone): Triple<String, Long, String> {
    val s = ev.startAt ?: return Triple("__tbd", Long.MAX_VALUE, "Date TBD")
    return try {
        val date = Instant.parse(s).toLocalDateTime(tz).date
        val key = date.toString() // YYYY-MM-DD
        val sort = date.toEpochDays().toLong()
        val dayNames = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val label = "${dayNames[date.dayOfWeek.ordinal]}, ${monthNames[date.monthNumber - 1]} ${date.dayOfMonth}"
        Triple(key, sort, label)
    } catch (_: Throwable) {
        Triple("__tbd", Long.MAX_VALUE, "Date TBD")
    }
}

/**
 * Bo tray tab metadata, keyed by parent-category. Sort order = tab order.
 * The parent set is a superset of Explore's `parentCategories` (carousels
 * edge fn): Bo additionally keeps Museums / Tours / Libraries / Lectures /
 * Community / Farmers Markets as their own tabs (Explore folds some into
 * Experiences or a dedicated rail; a concierge wants them granular).
 */
private val boBucketMeta: Map<String, Triple<String, Int, String>> = mapOf(
    "music" to Triple("music", 10, "Music"),
    "sports" to Triple("sports", 20, "Sports"),
    "outdoors" to Triple("outdoors", 25, "Outdoor"),
    "museums" to Triple("museums", 30, "Museums"),
    "arts" to Triple("arts", 35, "Arts"),
    "theatre" to Triple("theatre", 40, "Theatre"),
    "lectures" to Triple("lectures", 45, "Lectures & Talks"),
    "comedy" to Triple("comedy", 50, "Comedy"),
    "food" to Triple("food", 55, "Food & Drink"),
    "tours" to Triple("tours", 60, "Tours"),
    "for-kids" to Triple("for-kids", 65, "For Kids"),
    "libraries" to Triple("libraries", 70, "Libraries"),
    "community" to Triple("community", 75, "Community"),
    "nightlife" to Triple("nightlife", 80, "Nightlife"),
    "farmers-market" to Triple("farmers-market", 85, "Farmers Markets"),
)

/**
 * Leaf subcategory → parent, mirroring `subcategoryToParent` in
 * `supabase/functions/carousels/index.ts` VERBATIM (so Bo's tabs match the
 * Explore page exactly), plus Bo-only leaves (lectures/community/farmers-
 * market/arts synonyms) for the extra tabs above. Keep in sync with Explore.
 */
private val boSubToParent: Map<String, String> = mapOf(
    // Sports
    "hockey" to "sports", "basketball" to "sports", "baseball" to "sports", "football" to "sports",
    "soccer" to "sports", "college-sports" to "sports", "minor-league" to "sports",
    // Music
    "jazz" to "music", "rock" to "music", "hip-hop" to "music", "hip hop" to "music", "classical" to "music",
    "folk" to "music", "country" to "music", "open-mic" to "music", "electronic" to "music",
    "indie" to "music", "punk" to "music", "blues" to "music", "r&b" to "music", "rap" to "music",
    "concert" to "music", "pop" to "music", "alternative" to "music", "dj" to "music", "live-music" to "music",
    // Nightlife
    "bar" to "nightlife", "brewery" to "nightlife", "winery" to "nightlife", "pub" to "nightlife",
    "nightclub" to "nightlife", "wine-bar" to "nightlife", "craft-beer" to "nightlife", "club" to "nightlife",
    // Comedy
    "stand-up" to "comedy", "standup" to "comedy", "improv" to "comedy",
    // Arts (Explore: gallery/film/street-art → arts) + Bo arts synonyms
    "gallery" to "arts", "galleries" to "arts", "film" to "arts", "street-art" to "arts",
    "performance art" to "arts", "exhibit" to "arts", "exhibits" to "arts", "art" to "arts",
    "visual-art" to "arts", "visual art" to "arts", "craft" to "arts", "crafts" to "arts", "literary" to "arts",
    // Food
    "restaurant" to "food", "cafe" to "food", "bakery" to "food", "restaurant-week" to "food",
    // Theatre (Explore: opera/dance/musical → theatre)
    "opera" to "theatre", "dance" to "theatre", "musical" to "theatre", "play" to "theatre", "theater" to "theatre",
    // Bo-only granular tabs
    "museum" to "museums",
    "library" to "libraries",
    "tour" to "tours",
    "hiking" to "outdoors", "park" to "outdoors", "outdoor" to "outdoors",
    "kids" to "for-kids", "family" to "for-kids",
    "lecture" to "lectures", "seminar" to "lectures", "lecture/seminar" to "lectures", "book talk" to "lectures",
    "community/civic" to "community", "civic" to "community",
    "farmers market" to "farmers-market",
    // Residue
    "miscellaneous" to "other", "miscellane" to "other",
)

/** Resolve a raw category/subcategory token to a Bo parent bucket, or null. */
private fun resolveParentBucket(token: String): String? {
    if (token.isBlank()) return null
    if (boBucketMeta.containsKey(token)) return token // token is already a parent (arts, farmers-market, …)
    return boSubToParent[token] // leaf → parent, or null
}

/**
 * Map a free-text search query (Bo's `search_events` category/keyword — e.g.
 * "arts", "music", "jazz") to the tab bucket key it names, or null if it names
 * no recognizable category. Callers use this to scope a single-category browse
 * ("arts this weekend") to that category WITHOUT over-constraining a generic
 * date browse ("what's happening this weekend" → null → no scoping).
 */
fun categoryKeyForQuery(query: String): String? =
    resolveParentBucket(query.lowercase().trim())?.takeIf { it != "other" }

/**
 * Bucket an event by a friendly category tab, matching the Explore page's
 * taxonomy. Subcategory is the primary signal (it's the specific domain);
 * category is the fallback for rows whose subcategory is blank/uninformative.
 * "other"/"upcoming"/"always_on"/null category rows with no usable subcategory
 * land in Other. Comedy is also caught by venue/title heuristics because many
 * stand-up rows are mis-tagged category="arts" with no subcategory.
 */
fun computeEventCategoryKey(ev: Event): Triple<String, Int, String> {
    val sub = (ev.subcategory ?: "").lowercase().trim()
    val cat = (ev.category ?: "").lowercase().trim()
    val venue = (ev.venueName ?: "").lowercase()
    val title = ev.title.lowercase()

    // Comedy venue/title heuristic (mis-tagged stand-up) — only when the row
    // isn't already some other recognizable domain by subcategory.
    val subParent = resolveParentBucket(sub)
    if (subParent == null || subParent == "other") {
        if (venue.contains("comedy connection") || venue.contains("laugh boston") ||
            venue.contains("nick's comedy") || venue.contains("the wilbur") ||
            venue.endsWith(" comedy club") || venue.contains("comedy studio") ||
            title.contains("stand-up") || title.contains("standup") || title.contains("comedy night")
        ) return boBucketMeta.getValue("comedy")
    }

    val parent = subParent?.takeIf { it != "other" } ?: resolveParentBucket(cat)
    return parent?.let { boBucketMeta[it] } ?: Triple("__other", 999, "Other")
}
