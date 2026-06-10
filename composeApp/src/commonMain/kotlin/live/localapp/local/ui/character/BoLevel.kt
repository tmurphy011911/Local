package live.localapp.local.ui.character

import com.russhwolf.settings.Settings
import live.localapp.local.domain.model.StatusTier
import live.localapp.local.domain.model.boLevel

/**
 * The user's current Bo level (0–8), read synchronously from the tier cached by
 * ProgressViewModel on every stats load. Use this on surfaces that render before
 * badge data is available (splash, first-open, headers). Defaults to level 0
 * (New in Town) when nothing is cached yet.
 */
fun cachedBoLevel(): Int {
    val settings = Settings()
    // `bo_current_tier` is written on every stats load; `progress_last_tier` is
    // the persisted last-seen snapshot. Fall back to the latter so headers show
    // the right Bo even before a fresh stats load completes this session.
    val name = settings.getStringOrNull("bo_current_tier")
        ?: settings.getStringOrNull("progress_last_tier")
        ?: return 0
    return (StatusTier.entries.firstOrNull { it.name == name } ?: StatusTier.NEW_IN_TOWN).boLevel
}
