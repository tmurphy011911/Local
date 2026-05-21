package live.localapp.local.data.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Subscribes to Supabase Realtime updates on the `plans` table for the
 * signed-in user. Each incoming `INSERT`/`UPDATE`/`DELETE` is forwarded as a
 * lightweight event ([PlanRowChange]) so the [PlansViewModel] (or any other
 * observer) can re-fetch the affected plan and fold it into local state.
 *
 * We don't try to decode the row payload here — the realtime channel
 * delivers the *raw* JSON exactly as Postgres serialized it, which is a
 * different shape than `PlanRepositoryImpl` expects (snake_case vs.
 * `PlanDto`'s `@SerialName` mappings, attendees not joined, Bo-Web state
 * not hydrated, etc.). Re-fetching via [PlanRepository.getPlan] is one
 * extra round trip but keeps the hydration logic in one place.
 *
 * Lifecycle: call [start] when the user logs in, [stop] when they log out
 * or the app shuts down. The watcher owns its own [CoroutineScope] so it
 * survives ViewModel teardowns.
 */
class PlanRealtimeWatcher(
    private val supabase: SupabaseClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _changes = MutableSharedFlow<PlanRowChange>(extraBufferCapacity = 32)
    val changes: SharedFlow<PlanRowChange> = _changes.asSharedFlow()

    private var channel: RealtimeChannel? = null
    private var subscriptionJob: Job? = null

    /** Subscribe to row changes for [userId]. Idempotent — calling twice
     *  with the same user is a no-op; calling with a new user tears down
     *  the prior subscription first. */
    fun start(userId: String) {
        scope.launch {
            stopInternal()
            val ch = supabase.channel("plans:user=$userId")
            // postgresChangeFlow is the canonical Supabase-kt v3 Realtime
            // surface; the * filter delivers all action types and we
            // dispatch on `record`.
            val flow = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "plans"
                filter("user_id", FilterOperator.EQ, userId)
            }
            subscriptionJob = flow.onEach { action ->
                when (action) {
                    is PostgresAction.Insert -> emitChange(action.record, kind = ChangeKind.INSERT)
                    is PostgresAction.Update -> emitChange(action.record, kind = ChangeKind.UPDATE)
                    is PostgresAction.Delete -> {
                        // On delete, oldRecord carries the previous row.
                        val planId = action.oldRecord["id"]?.jsonPrimitive?.contentOrNull
                        if (planId != null) {
                            _changes.tryEmit(PlanRowChange(planId, ChangeKind.DELETE))
                        }
                    }
                    else -> { /* SELECT / etc. — not used */ }
                }
            }.launchIn(scope)
            ch.subscribe()
            channel = ch
        }
    }

    /** Tear down the active subscription. Safe to call when nothing is
     *  subscribed. */
    suspend fun stop() {
        stopInternal()
    }

    private suspend fun stopInternal() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        channel?.let {
            try { it.unsubscribe() } catch (_: Throwable) {}
            try { supabase.removeChannel(it) } catch (_: Throwable) {}
        }
        channel = null
    }

    private fun emitChange(record: JsonObject, kind: ChangeKind) {
        val planId = record["id"]?.jsonPrimitive?.contentOrNull ?: return
        _changes.tryEmit(PlanRowChange(planId, kind))
    }
}

enum class ChangeKind { INSERT, UPDATE, DELETE }

data class PlanRowChange(
    val planId: String,
    val kind: ChangeKind,
)

private suspend fun SupabaseClient.removeChannel(channel: RealtimeChannel) {
    realtime.removeChannel(channel)
}

private val SupabaseClient.realtime
    get() = pluginManager.getPlugin(io.github.jan.supabase.realtime.Realtime)
