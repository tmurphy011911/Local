package live.localapp.local.feature.attribution

import com.russhwolf.settings.Settings

/**
 * Where this install came from, captured before signup so the first
 * `user_profiles` write can stamp `signup_source` / `signup_referrer`.
 *
 * Sources today:
 *  - Android Play Install Referrer (`utm_source` from any Play link with a
 *    `referrer` param; organic installs report `google-play`).
 *  - A deferred share link on either platform records `share-link`.
 * iOS has no install-referrer API, so non-share iOS installs stay null and
 * surface as "unknown" in BI; per-channel iOS tracking lives in App Store
 * Connect campaign links.
 *
 * First writer wins: the install-time signal must not be overwritten by later,
 * weaker signals (e.g. a share link opened weeks after a campaign install).
 * Values persist across launches until the profile insert reads them.
 */
object SignupAttribution {
    private const val KEY_SOURCE = "signup_attribution_source"
    private const val KEY_REFERRER = "signup_attribution_referrer"

    private val settings = Settings()

    fun record(source: String?, rawReferrer: String? = null) {
        val value = source?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (settings.getStringOrNull(KEY_SOURCE) != null) return
        settings.putString(KEY_SOURCE, value.take(100))
        rawReferrer?.let { settings.putString(KEY_REFERRER, it.take(500)) }
    }

    fun source(): String? = settings.getStringOrNull(KEY_SOURCE)

    fun rawReferrer(): String? = settings.getStringOrNull(KEY_REFERRER)
}
