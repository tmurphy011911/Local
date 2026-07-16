package live.localapp.local.platform

/**
 * Present the platform-native home-city editor (iOS = native SwiftUI via the
 * HomeCityEditBridge; Android returns false so the caller falls back to the
 * shared Compose editor).
 *
 * [onConfirm] is invoked by the native UI when the user confirms a city; it runs
 * the change_home_city RPC and reports back through [done].
 *
 * @return true if a native editor was presented, false otherwise.
 */
expect fun presentNativeHomeCityEditor(
    onConfirm: (
        name: String,
        lat: Double,
        lng: Double,
        placeId: String?,
        done: (ok: Boolean, rateLimited: Boolean, nextAllowedAt: String?) -> Unit,
    ) -> Unit,
): Boolean
