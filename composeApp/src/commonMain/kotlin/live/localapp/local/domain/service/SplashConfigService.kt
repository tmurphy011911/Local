package live.localapp.local.domain.service

import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Splash kill switch — single flag, default on. The full editorial splash
 * plays every cold start; the only override is a remote off-switch that
 * collapses to a static wordmark frame in case the animation needs to be
 * killed without a new app release.
 *
 * The Supabase fetch is best-effort: any failure leaves the cached value
 * unchanged. Default is fail-open (animation enabled).
 */
class SplashConfigService(
    private val supabaseClient: SupabaseClient,
    private val settings: Settings = Settings(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Returns the cached animation-enabled flag (default true). Sync. */
    fun isAnimationEnabled(): Boolean =
        settings.getBoolean(KEY_ANIM_ENABLED, true)

    /**
     * Best-effort remote refresh — kicked off at startup, never awaited by
     * the splash. The cached value is read synchronously for *this* cold
     * start; any update from this fetch applies on the *next* launch.
     */
    fun refreshFromRemoteAsync() {
        scope.launch {
            try {
                val row = withContext(Dispatchers.Default) {
                    supabaseClient.from("app_config")
                        .select { filter { eq("key", REMOTE_KEY_ANIM) } }
                        .decodeSingleOrNull<AppConfigRow>()
                }
                val remoteValue = row?.value?.toBooleanStrictOrNull() ?: return@launch
                settings.putBoolean(KEY_ANIM_ENABLED, remoteValue)
            } catch (_: Throwable) {
                // Table may not exist yet, network down, RLS, etc. — fall
                // through. The cached / default-true value remains in effect.
            }
        }
    }

    @Serializable
    private data class AppConfigRow(val key: String, val value: String)

    companion object {
        private const val KEY_ANIM_ENABLED = "splash_animation_enabled"
        private const val REMOTE_KEY_ANIM = "splash_animation_enabled"
    }
}
