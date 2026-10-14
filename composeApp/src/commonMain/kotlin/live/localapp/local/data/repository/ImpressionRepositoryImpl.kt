package live.localapp.local.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.repository.ImpressionItem
import live.localapp.local.domain.repository.ImpressionRepository
import live.localapp.local.domain.repository.InteractionType
import live.localapp.local.domain.repository.UserPreferencesRepository

@Serializable
private data class EventImpressionDto(
    @SerialName("user_id") val userId: String,
    @SerialName("item_id") val itemId: String,
    @SerialName("item_type") val itemType: String,
    @SerialName("surface") val surface: String,
    @SerialName("position") val position: Int? = null,
    @SerialName("actor_lat") val actorLat: Double? = null,
    @SerialName("actor_lng") val actorLng: Double? = null,
)

class ImpressionRepositoryImpl(
    private val supabaseClient: SupabaseClient,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ImpressionRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    // Log each (surface,item) at most once per session so scrolling a rail back
    // and forth doesn't inflate impression counts.
    private val seen = mutableSetOf<String>()
    private val buffer = mutableListOf<EventImpressionDto>()

    private companion object { const val FLUSH_SIZE = 25 }

    override fun recordImpressions(
        items: List<ImpressionItem>,
        surface: String,
        actorLat: Double?,
        actorLng: Double?,
    ) {
        if (items.isEmpty()) return
        val userId = userPreferencesRepository.getLocalUserId() ?: return
        scope.launch {
            val toFlush = mutableListOf<EventImpressionDto>()
            mutex.withLock {
                for (item in items) {
                    if (!seen.add("$surface:${item.itemId}")) continue
                    buffer.add(
                        EventImpressionDto(
                            userId = userId,
                            itemId = item.itemId,
                            itemType = when (item.type) {
                                InteractionType.EVENT -> "event"
                                InteractionType.PLACE -> "place"
                            },
                            surface = surface,
                            position = item.position,
                            actorLat = actorLat,
                            actorLng = actorLng,
                        )
                    )
                }
                if (buffer.size >= FLUSH_SIZE) {
                    toFlush.addAll(buffer)
                    buffer.clear()
                }
            }
            if (toFlush.isNotEmpty()) insert(toFlush)
        }
    }

    override fun flush() {
        scope.launch {
            val toFlush = mutableListOf<EventImpressionDto>()
            mutex.withLock {
                if (buffer.isEmpty()) return@withLock
                toFlush.addAll(buffer)
                buffer.clear()
            }
            if (toFlush.isNotEmpty()) insert(toFlush)
        }
    }

    override fun clearSession() {
        scope.launch {
            mutex.withLock {
                seen.clear()
                buffer.clear()
            }
        }
    }

    private suspend fun insert(rows: List<EventImpressionDto>) {
        try {
            supabaseClient.from("event_impressions").insert(rows)
        } catch (_: Exception) {
            // Best-effort signal — never surface to the user.
        }
    }
}
