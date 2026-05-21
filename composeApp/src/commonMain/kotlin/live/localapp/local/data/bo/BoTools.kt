package live.localapp.local.data.bo

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import live.localapp.local.Config
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.UserPreferencesRepository

/**
 * Shared implementations of the Claude tools both Bo surfaces register.
 *
 * Tools that produced byte-for-byte identical results in both VMs live here
 * — `get_event_details`, `save_web_finding` — so any future fix lands on
 * both surfaces at once. `search_events` and `search_venues` stay in their
 * owning VMs because the output shapes legitimately differ (the create-plan
 * surface emits a date+category grouped view that the per-plan editor
 * doesn't need; trying to share would require flag soup).
 */
class BoTools(
    private val eventRepo: EventRepository,
    private val userPrefsRepo: UserPreferencesRepository,
    private val httpClient: HttpClient = HttpClient(),
) {

    /** Caches Event objects returned by search_events so a follow-up
     *  get_event_details can answer without a second round-trip. Owned by
     *  BoTools so both surfaces share the same cache and so resetting the
     *  chat clears it from one place. */
    val searchResultCache: MutableMap<String, Event> = mutableMapOf()

    /** Caches the enriched payload returned from save-bo-web-finding,
     *  keyed by the issued id. Slot builders read from this when shaping
     *  Bo-Web slots so the resulting card has the venue's address / coords
     *  / image without waiting on the verifier round-trip. */
    val boWebFindingsCache: MutableMap<String, JsonObject> = mutableMapOf()

    /** Look up an event by id and emit a human-readable detail block. */
    suspend fun getEventDetails(input: JsonObject): String {
        val eventId = input["event_id"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error": "event_id is required"}"""
        val event = searchResultCache[eventId]
            ?: eventRepo.getEvent(eventId)
            ?: return """{"error": "Event not found with id: $eventId"}"""
        searchResultCache[eventId] = event
        return buildString {
            appendLine("ID: ${event.id}")
            appendLine("Title: ${event.title}")
            appendLine("Venue: ${event.venueName ?: "TBD"} (${event.venueCity})")
            event.venueAddress?.let { appendLine("Address: $it") }
            event.startAt?.let { appendLine("Date: ${event.dateDisplay}") }
            appendLine("Free: ${event.isFree}")
            if (event.priceDisplay.isNotBlank()) appendLine("Price: ${event.priceDisplay}")
            event.description?.let { appendLine("Description: ${it.take(800)}") }
            event.ticketUrl?.let { appendLine("Tickets: $it") }
            event.imageUrl?.let { appendLine("Image: $it") }
            val cat = event.category ?: "?"
            val sub = event.subcategory?.let { " / $it" } ?: ""
            appendLine("Category: $cat$sub")
        }
    }

    /** Forward a Bo-discovered event/venue to the save-bo-web-finding edge
     *  function. The response carries an id Bo must use as `event_id` on
     *  the next slot, plus enriched fields (Google Places address, coords,
     *  image) — those are merged with the input and cached under
     *  [boWebFindingsCache] for the slot builder. */
    suspend fun saveWebFinding(input: JsonObject): String {
        val userId = userPrefsRepo.getLocalUserId() ?: return """{"error": "no_user"}"""
        val kind = input["kind"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error": "kind required (event|venue)"}"""
        val payload = buildJsonObject {
            put("user_id", JsonPrimitive(userId))
            put("kind", JsonPrimitive(kind))
            put("payload", input)
        }
        return try {
            val res = httpClient.post("${Config.SUPABASE_URL}/functions/v1/save-bo-web-finding") {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            val body = res.bodyAsText()
            if (res.status.value !in 200..299) {
                println("BoTools.saveWebFinding error: ${res.status.value} ${body.take(300)}")
                """{"error": "save failed"}"""
            } else {
                runCatching {
                    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(body) as? JsonObject
                    val id = parsed?.get("id")?.jsonPrimitive?.contentOrNull
                    if (id != null && parsed != null) {
                        val merged = buildJsonObject {
                            for ((k, v) in input) put(k, v)
                            // Enriched fields override null inputs only.
                            parsed["venue_address"]?.let { if (input["venue_address"] == null) put("venue_address", it) }
                            parsed["venue_lat"]?.let { if (input["venue_lat"] == null) put("venue_lat", it) }
                            parsed["venue_lng"]?.let { if (input["venue_lng"] == null) put("venue_lng", it) }
                            parsed["image_url"]?.let { if (input["image_url"] == null) put("image_url", it) }
                        }
                        boWebFindingsCache[id] = merged
                    }
                }
                body
            }
        } catch (e: Throwable) {
            println("BoTools.saveWebFinding exception: ${e.message}")
            """{"error": "network: ${e.message}"}"""
        }
    }

    /** Drop every cached Event. Called when the chat resets. */
    fun resetCache() {
        searchResultCache.clear()
    }
}

/**
 * Resolves a city/town name to a (lat, lng) pair so search_events can be
 * pinned to the named location instead of silently falling back to user
 * coords (which would surface results from somewhere else and violate the
 * geographic-integrity rule).
 *
 * Lifted from the chat surface verbatim — both surfaces need this when
 * they accept a `location` param on a search tool. The map is northeast-
 * heavy because that's where the app is live; expand as new metros open.
 *
 * Returns null when the name doesn't resolve. Callers MUST handle null —
 * never substitute user coords for an unresolved name.
 */
fun geocodeCity(city: String): Pair<Double, Double>? {
    val key = city.lowercase().trim()
    return KNOWN_CITIES[key] ?: KNOWN_CITIES.entries.firstOrNull { key.contains(it.key) }?.value
}

private val KNOWN_CITIES: Map<String, Pair<Double, Double>> = mapOf(
    "boston" to (42.3601 to -71.0589),
    "cambridge" to (42.3736 to -71.1097),
    "somerville" to (42.3876 to -71.0995),
    "waltham" to (42.3765 to -71.2356),
    "brookline" to (42.3318 to -71.1212),
    "newton" to (42.3370 to -71.2092),
    "worcester" to (42.2626 to -71.8023),
    "springfield" to (42.1015 to -72.5898),
    "lowell" to (42.6334 to -71.3162),
    "providence" to (41.8240 to -71.4128),
    "newport" to (41.4901 to -71.3128),
    "new york" to (40.7128 to -74.0060),
    "nyc" to (40.7128 to -74.0060),
    "manhattan" to (40.7831 to -73.9712),
    "brooklyn" to (40.6782 to -73.9442),
    "portland" to (43.6591 to -70.2568),
    "portsmouth" to (43.0718 to -70.7626),
    "burlington" to (44.4759 to -73.2121),
    "hartford" to (41.7658 to -72.6734),
    "new haven" to (41.3083 to -72.9279),
    "northampton" to (42.3251 to -72.6412),
    "salem" to (42.5195 to -70.8967),
    "cape cod" to (41.6688 to -70.2962),
    "pittsfield" to (42.4501 to -73.2454),
    "philadelphia" to (39.9526 to -75.1652),
    // Cape Cod towns + villages — Bo asks by name ("dancing in Dennis").
    // Without these, search silently falls back to user coords, surfacing
    // Boston results 90+ miles away.
    "dennis" to (41.7370 to -70.1939),
    "west dennis" to (41.6628 to -70.1736),
    "south dennis" to (41.7228 to -70.1700),
    "east dennis" to (41.7484 to -70.1631),
    "dennis port" to (41.6603 to -70.1289),
    "hyannis" to (41.6529 to -70.2829),
    "barnstable" to (41.7003 to -70.2994),
    "yarmouth" to (41.7029 to -70.2280),
    "south yarmouth" to (41.6717 to -70.1989),
    "west yarmouth" to (41.6451 to -70.2417),
    "harwich" to (41.6817 to -70.0739),
    "harwich port" to (41.6726 to -70.0742),
    "chatham" to (41.6815 to -69.9597),
    "orleans" to (41.7898 to -69.9897),
    "brewster" to (41.7587 to -70.0820),
    "eastham" to (41.8298 to -69.9747),
    "wellfleet" to (41.9337 to -69.9658),
    "truro" to (41.9959 to -70.0508),
    "north truro" to (42.0354 to -70.0858),
    "provincetown" to (42.0584 to -70.1786),
    "falmouth" to (41.5512 to -70.6151),
    "woods hole" to (41.5240 to -70.6717),
    "mashpee" to (41.6487 to -70.4795),
    "sandwich" to (41.7590 to -70.4937),
    "bourne" to (41.7406 to -70.5969),
    "buzzards bay" to (41.7460 to -70.6184),
    // Islands
    "nantucket" to (41.2835 to -70.0995),
    "martha's vineyard" to (41.4537 to -70.6035),
    "marthas vineyard" to (41.4537 to -70.6035),
    "vineyard haven" to (41.4537 to -70.6035),
    "edgartown" to (41.3893 to -70.5131),
    "oak bluffs" to (41.4543 to -70.5570),
)
