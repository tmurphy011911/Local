package live.localapp.local.domain.service

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.platform.getMarketingVersion

@Serializable
private data class AppConfigRow(val key: String, val value: String)

enum class VersionStatus {
    UP_TO_DATE,
    UPDATE_AVAILABLE,  // Dismissable banner
    FORCE_UPDATE,      // Full-screen blocker
}

data class VersionCheckResult(
    val status: VersionStatus,
    val message: String = "",
    val storeUrl: String = "",
    // Soft-paywall nudge cadence: show the (dismissible) paywall every Nth app
    // open for non-premium users. Read from the `paywall_nudge_interval`
    // app_config key so it's tunable without a release; defaults to 3.
    val paywallNudgeInterval: Int = DEFAULT_PAYWALL_NUDGE_INTERVAL,
)

/** Default nudge cadence when app_config is unavailable/unset. */
const val DEFAULT_PAYWALL_NUDGE_INTERVAL = 3

class VersionCheckService(
    private val supabaseClient: SupabaseClient,
) {
    companion object {
        // Fallback only. The running version is read at runtime from the
        // platform build (getMarketingVersion) so the check tracks the real
        // shipped marketing version; this constant is used only if that read
        // returns blank. Keep it at the current release.
        const val CURRENT_VERSION = "1.1.0"

        const val IOS_STORE_URL = "https://apps.apple.com/app/local/id6761425705"
        const val ANDROID_STORE_URL = "https://play.google.com/store/apps/details?id=live.localapp.local"
    }

    suspend fun check(): VersionCheckResult {
        return try {
            val configs = supabaseClient.from("app_config")
                .select {
                    filter {
                        isIn("key", listOf("min_version", "latest_version", "force_message", "paywall_nudge_interval"))
                    }
                }
                .decodeList<AppConfigRow>()

            // The version we compare is the REAL shipped marketing version
            // (iOS CFBundleShortVersionString / Android versionName), falling
            // back to the constant only if the platform read comes back blank.
            val currentVersion = runCatching { getMarketingVersion() }
                .getOrDefault("").ifBlank { CURRENT_VERSION }

            val configMap = configs.associate { it.key to it.value }
            val minVersion = configMap["min_version"] ?: "1.0"
            val latestVersion = configMap["latest_version"] ?: currentVersion
            val forceMessage = configMap["force_message"] ?: "Please update Local to continue."
            // Positive int only; anything unparseable/≤0 falls back to the default
            // (a 0/negative value would otherwise disable the nudge entirely).
            val nudgeInterval = configMap["paywall_nudge_interval"]?.toIntOrNull()
                ?.takeIf { it > 0 } ?: DEFAULT_PAYWALL_NUDGE_INTERVAL

            when {
                compareVersions(currentVersion, minVersion) < 0 -> {
                    VersionCheckResult(VersionStatus.FORCE_UPDATE, forceMessage, paywallNudgeInterval = nudgeInterval)
                }
                compareVersions(currentVersion, latestVersion) < 0 -> {
                    VersionCheckResult(VersionStatus.UPDATE_AVAILABLE, "A new version of Local is available.", paywallNudgeInterval = nudgeInterval)
                }
                else -> {
                    VersionCheckResult(VersionStatus.UP_TO_DATE, paywallNudgeInterval = nudgeInterval)
                }
            }
        } catch (e: Throwable) {
            println("VersionCheck: error ${e.message}")
            VersionCheckResult(VersionStatus.UP_TO_DATE) // Fail open (default nudge interval)
        }
    }

    /** Compare version strings like "1.0" vs "1.1". Returns negative if a < b. */
    private fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(aParts.size, bParts.size)
        for (i in 0 until maxLen) {
            val av = aParts.getOrElse(i) { 0 }
            val bv = bParts.getOrElse(i) { 0 }
            if (av != bv) return av - bv
        }
        return 0
    }

}
