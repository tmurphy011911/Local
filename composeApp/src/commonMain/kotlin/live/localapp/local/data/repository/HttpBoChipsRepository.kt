package live.localapp.local.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import live.localapp.local.Config
import live.localapp.local.domain.repository.BoChipsRepository

@Serializable
private data class ChipsRequest(
    val user_id: String,
    val local_hour: Int,
    val day_of_week: Int,
    val lat: Double? = null,
    val lng: Double? = null,
)

@Serializable
private data class ChipsResponse(
    val chips: List<String> = emptyList(),
    val cached: Boolean = false,
)

/**
 * Calls the `bo-chips` edge function on chat open. Server caches by
 * user + period (~6h) so re-opens within the same window are <100ms.
 * Returns empty list on failure — viewmodel substitutes a rules-based
 * fallback so the user always sees chips.
 */
class HttpBoChipsRepository : BoChipsRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val httpClient = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(ContentEncoding) {
            gzip()
            deflate()
        }
        install(HttpTimeout) {
            // Cold Haiku call ~2–5s. Cap at 10s so a slow call doesn't
            // hold the chat welcome screen visibly — the viewmodel
            // races this against the greeting and shows fallback chips
            // if we miss the window.
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 10_000
        }
    }

    private val endpoint = "${Config.SUPABASE_URL}/functions/v1/bo-chips"

    override suspend fun getChips(
        userId: String,
        localHour: Int,
        dayOfWeek: Int,
        lat: Double?,
        lng: Double?,
    ): List<String> {
        return try {
            val response = httpClient.post(endpoint) {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                contentType(ContentType.Application.Json)
                setBody(ChipsRequest(
                    user_id = userId,
                    local_hour = localHour,
                    day_of_week = dayOfWeek,
                    lat = lat,
                    lng = lng,
                ))
            }
            val body = response.bodyAsText()
            val parsed = json.decodeFromString<ChipsResponse>(body)
            println("[BoChips repo] got ${parsed.chips.size} chips (cached=${parsed.cached})")
            parsed.chips
        } catch (e: Throwable) {
            println("[BoChips repo] FAILED: ${e::class.simpleName}: ${e.message}")
            emptyList()
        }
    }
}
