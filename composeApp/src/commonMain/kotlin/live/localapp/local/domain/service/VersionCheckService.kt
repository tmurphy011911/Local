package live.localapp.local.domain.service

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
)

class VersionCheckService(
    private val supabaseClient: SupabaseClient,
) {
    companion object {
        // Current app version — update on each release
        const val CURRENT_VERSION = "1.0"

        const val IOS_STORE_URL = "https://apps.apple.com/app/local/id6761425705"
        const val ANDROID_STORE_URL = "https://play.google.com/store/apps/details?id=live.localapp.local"
    }

    suspend fun check(): VersionCheckResult {
        return try {
            val configs = supabaseClient.from("app_config")
                .select {
                    filter {
                        isIn("key", listOf("min_version", "latest_version", "force_message"))
                    }
                }
                .decodeList<AppConfigRow>()

            val configMap = configs.associate { it.key to it.value }
            val minVersion = configMap["min_version"] ?: "1.0"
            val latestVersion = configMap["latest_version"] ?: CURRENT_VERSION
            val forceMessage = configMap["force_message"] ?: "Please update Local to continue."

            when {
                compareVersions(CURRENT_VERSION, minVersion) < 0 -> {
                    VersionCheckResult(VersionStatus.FORCE_UPDATE, forceMessage)
                }
                compareVersions(CURRENT_VERSION, latestVersion) < 0 -> {
                    VersionCheckResult(VersionStatus.UPDATE_AVAILABLE, "A new version of Local is available.")
                }
                else -> {
                    VersionCheckResult(VersionStatus.UP_TO_DATE)
                }
            }
        } catch (e: Throwable) {
            println("VersionCheck: error ${e.message}")
            VersionCheckResult(VersionStatus.UP_TO_DATE) // Fail open
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
