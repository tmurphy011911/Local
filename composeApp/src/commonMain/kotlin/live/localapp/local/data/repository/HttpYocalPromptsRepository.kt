package live.localapp.local.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import live.localapp.local.Config
import live.localapp.local.domain.repository.YocalPromptsRepository

@Serializable
private data class YocalPromptsResponse(
    val prompts: List<String> = emptyList(),
    val cached: Boolean = false,
)

/**
 * Fetches Home typewriter suggestions from the `yocal-prompts` edge function.
 *
 * - One short request on Home mount (the ViewModel calls once per location).
 * - 24h per-grid cache on the server so the client doesn't have to worry
 *   about spamming. A simple in-memory guard here skips duplicate calls
 *   within the same session.
 * - Returns empty list on any failure so the UI can fall back to the
 *   hardcoded `FALLBACK_YOCAL_PROMPTS`.
 */
class HttpYocalPromptsRepository : YocalPromptsRepository {

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
            // Generating 200 prompts through Claude Haiku takes ~15–30s cold.
            // 20s was too tight and was timing out before Claude responded —
            // giving it 60s.
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
    }

    private val endpoint = "${Config.SUPABASE_URL}/functions/v1/yocal-prompts"

    override suspend fun getPrompts(lat: Double, lng: Double): List<String> {
        // No client-side cache — the edge function already caches 24h per
        // geo grid and serves hits in <100ms. A per-session client memo
        // just adds another place for state to go stale across metro
        // switches (Boston → Philly kept returning Boston prompts).
        return try {
            println("[YocalPrompts repo] GET $endpoint?lat=$lat&lng=$lng")
            val response = httpClient.get(endpoint) {
                header("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
                parameter("lat", lat)
                parameter("lng", lng)
            }
            val body = response.bodyAsText()
            println("[YocalPrompts repo] HTTP ${response.status.value}, body len ${body.length}")
            val parsed = json.decodeFromString<YocalPromptsResponse>(body)
            println("[YocalPrompts repo] parsed ${parsed.prompts.size} prompts")
            parsed.prompts
        } catch (e: Throwable) {
            println("[YocalPrompts repo] FAILED: ${e::class.simpleName}: ${e.message}")
            e.printStackTrace()
            emptyList()
        }
    }
}
