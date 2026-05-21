package live.localapp.local.data.sync

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Local, durable queue of pending [PlanMutation]s. The queue is the source of
 * truth for "edits the user has made that haven't yet been confirmed by the
 * server". Survives app kills (persisted to multiplatform-settings) and
 * reboots, so an edit made offline or interrupted by a crash is retried on
 * next launch.
 *
 * Records leave the log only on successful sync. Failed records stay with
 * `attempts > 0` and `lastError` set so the UI can surface them.
 */
interface MutationLog {
    /** All pending records, ordered oldest first. */
    val pending: StateFlow<List<MutationRecord>>

    /** Append a mutation. Returns the persisted record. */
    suspend fun enqueue(mutation: PlanMutation): MutationRecord

    /** Drop a record after a successful sync. */
    suspend fun markSynced(opId: String)

    /** Bump attempt count and store the error message. */
    suspend fun markFailed(opId: String, error: String)

    /** Drop every pending op for a plan id (used after a successful full
     *  delete, or when the user logs out). */
    suspend fun clearForPlan(planId: String)

    /** Wipe the log. */
    suspend fun clear()
}

@Serializable
data class MutationRecord(
    val opId: String,
    val mutation: PlanMutation,
    val attempts: Int = 0,
    val lastError: String? = null,
    val lastTriedAt: Long = 0L,
)

/**
 * Multiplatform-settings backed implementation. Persists the entire queue
 * as one JSON array under [STORAGE_KEY]. Bounded at [MAX_RECORDS] — overflow
 * evicts the oldest. In normal use the queue stays under 10 entries.
 */
class MutationLogImpl(
    private val settings: Settings,
) : MutationLog {
    private val mutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    private val _pending = MutableStateFlow<List<MutationRecord>>(emptyList())
    override val pending: StateFlow<List<MutationRecord>> = _pending.asStateFlow()

    init {
        // Load any persisted ops on construction so the syncer can drain
        // them at app launch.
        runCatching {
            val raw = settings.getStringOrNull(STORAGE_KEY) ?: return@runCatching
            val records = json.decodeFromString<List<MutationRecord>>(raw)
            _pending.value = records
        }.onFailure {
            // Corrupted blob — better to drop than to crash on every launch.
            println("MutationLog: failed to read storage (${it.message}); resetting")
            settings.remove(STORAGE_KEY)
        }
    }

    override suspend fun enqueue(mutation: PlanMutation): MutationRecord = mutex.withLock {
        val record = MutationRecord(opId = mutation.opId, mutation = mutation)
        val updated = (_pending.value + record).takeLast(MAX_RECORDS)
        _pending.value = updated
        persist(updated)
        record
    }

    override suspend fun markSynced(opId: String) = mutex.withLock {
        val updated = _pending.value.filterNot { it.opId == opId }
        if (updated.size != _pending.value.size) {
            _pending.value = updated
            persist(updated)
        }
    }

    override suspend fun markFailed(opId: String, error: String) = mutex.withLock {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val updated = _pending.value.map {
            if (it.opId == opId) it.copy(
                attempts = it.attempts + 1,
                lastError = error,
                lastTriedAt = now,
            ) else it
        }
        _pending.value = updated
        persist(updated)
    }

    override suspend fun clearForPlan(planId: String) = mutex.withLock {
        val updated = _pending.value.filterNot { it.mutation.planId == planId }
        if (updated.size != _pending.value.size) {
            _pending.value = updated
            persist(updated)
        }
    }

    override suspend fun clear() = mutex.withLock {
        _pending.value = emptyList()
        settings.remove(STORAGE_KEY)
    }

    private fun persist(records: List<MutationRecord>) {
        runCatching {
            settings.putString(STORAGE_KEY, json.encodeToString(records))
        }.onFailure {
            println("MutationLog: failed to persist (${it.message})")
        }
    }

    companion object {
        private const val STORAGE_KEY = "pending_mutations_v1"
        private const val MAX_RECORDS = 200
    }
}

