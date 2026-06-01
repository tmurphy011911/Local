package live.localapp.local.data.featureflags

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-controlled feature flags backed by the `feature_flags` Supabase
 * table. Provides a fail-safe read API (`get` defaults to TRUE when a flag
 * is missing — the Bo overhaul flags all guard *resilience* behavior, so
 * the safe-default-on posture means a Supabase outage degrades to the new
 * code path, not the old one) plus deterministic per-user canary bucketing
 * via SHA-256.
 *
 * Wired into Koin as a singleton (see [live.localapp.local.di.appModule]).
 * Call [init] once at startup (App.kt, after auth) — the same lifecycle
 * point that resolves PlanSyncer/PlanRealtimeWatcher today.
 */
class FeatureFlagsRepository(
    private val supabase: SupabaseClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _flags = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val flags: StateFlow<Map<String, Boolean>> = _flags.asStateFlow()

    private var channel: RealtimeChannel? = null
    private var subscriptionJob: Job? = null

    @Serializable
    private data class FlagRow(
        @SerialName("name") val name: String,
        @SerialName("enabled") val enabled: Boolean,
    )

    /** Loads all flags into the in-memory cache and subscribes to realtime
     *  updates so the cache reflects flips within ~1s of a write. Idempotent:
     *  calling twice re-runs the initial load and replaces the subscription. */
    suspend fun init() {
        refresh()
        subscribe()
    }

    /** Fail-safe read: returns the cached value, defaulting to TRUE when the
     *  flag is absent (network failure, missing seed row, etc.). The Bo
     *  overhaul flags are all "resilience on" by default — see the migration
     *  seed list. */
    fun get(flag: String): Boolean = _flags.value[flag] ?: true

    /**
     * Deterministic per-user canary bucketing.
     *
     * Returns true iff the user falls inside the leading [percentage]% of
     * the SHA-256(userId + flag) keyspace. Same user + same flag always
     * lands in the same bucket, so a user either consistently sees the new
     * code path or consistently doesn't across sessions.
     *
     * Also respects the flag's master switch: if [get] returns false, this
     * returns false regardless of percentage.
     */
    fun enabledForUser(flag: String, userId: String, percentage: Int): Boolean {
        if (!get(flag)) return false
        if (percentage <= 0) return false
        if (percentage >= 100) return true
        val digest = sha256((userId + flag).encodeToByteArray())
        // Take the first 4 bytes as an unsigned 32-bit int → mod 100.
        val bucket = (
            ((digest[0].toInt() and 0xFF) shl 24) or
            ((digest[1].toInt() and 0xFF) shl 16) or
            ((digest[2].toInt() and 0xFF) shl 8) or
            (digest[3].toInt() and 0xFF)
        ).toUInt().toLong() % 100L
        return bucket < percentage
    }

    private suspend fun refresh() {
        try {
            val rows = supabase.from("feature_flags")
                .select()
                .decodeList<FlagRow>()
            _flags.value = rows.associate { it.name to it.enabled }
        } catch (_: Throwable) {
            // Swallow — `get` falls back to TRUE so a load failure leaves the
            // app in the resilient default state rather than crashing startup.
        }
    }

    private fun subscribe() {
        scope.launch {
            stopInternal()
            val ch = supabase.channel("feature_flags")
            val flow = ch.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "feature_flags"
            }
            subscriptionJob = flow.onEach { _ ->
                // Any change → re-pull the whole table. It's seven rows; the
                // round-trip is cheaper than maintaining per-action diff logic.
                refresh()
            }.launchIn(scope)
            ch.subscribe()
            channel = ch
        }
    }

    private suspend fun stopInternal() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        channel?.let {
            try { it.unsubscribe() } catch (_: Throwable) {}
            try { supabase.realtime.removeChannel(it) } catch (_: Throwable) {}
        }
        channel = null
    }
}

private val SupabaseClient.realtime
    get() = pluginManager.getPlugin(io.github.jan.supabase.realtime.Realtime)

// ──────────────────────────────────────────────────────────────────────
// Pure-Kotlin SHA-256. Inlined so the canary bucketing works on every KMP
// target without dragging in okio / cryptography-kotlin. Only the first 4
// bytes are consumed downstream, but the full digest is computed for
// correctness so future callers can rely on the standard hash.
// ──────────────────────────────────────────────────────────────────────
private fun sha256(message: ByteArray): ByteArray {
    val k = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )
    var h0 = 0x6a09e667.toInt(); var h1 = 0xbb67ae85.toInt()
    var h2 = 0x3c6ef372.toInt(); var h3 = 0xa54ff53a.toInt()
    var h4 = 0x510e527f.toInt(); var h5 = 0x9b05688c.toInt()
    var h6 = 0x1f83d9ab.toInt(); var h7 = 0x5be0cd19.toInt()

    val bitLen = message.size.toLong() * 8L
    val padded = ByteArray(((message.size + 9 + 63) / 64) * 64)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (i in 0..7) padded[padded.size - 1 - i] = ((bitLen ushr (8 * i)) and 0xFFL).toByte()

    val w = IntArray(64)
    var off = 0
    while (off < padded.size) {
        for (i in 0..15) {
            w[i] = ((padded[off + 4 * i].toInt() and 0xFF) shl 24) or
                   ((padded[off + 4 * i + 1].toInt() and 0xFF) shl 16) or
                   ((padded[off + 4 * i + 2].toInt() and 0xFF) shl 8) or
                   (padded[off + 4 * i + 3].toInt() and 0xFF)
        }
        for (i in 16..63) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h0; var b = h1; var c = h2; var d = h3
        var e = h4; var f = h5; var g = h6; var h = h7
        for (i in 0..63) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = h + s1 + ch + k[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val mj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + mj
            h = g; g = f; f = e; e = d + t1
            d = c; c = b; b = a; a = t1 + t2
        }
        h0 += a; h1 += b; h2 += c; h3 += d
        h4 += e; h5 += f; h6 += g; h7 += h
        off += 64
    }
    val out = ByteArray(32)
    for ((idx, v) in listOf(h0, h1, h2, h3, h4, h5, h6, h7).withIndex()) {
        out[4 * idx] = (v ushr 24).toByte()
        out[4 * idx + 1] = (v ushr 16).toByte()
        out[4 * idx + 2] = (v ushr 8).toByte()
        out[4 * idx + 3] = v.toByte()
    }
    return out
}
