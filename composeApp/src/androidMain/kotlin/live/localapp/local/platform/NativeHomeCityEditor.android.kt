package live.localapp.local.platform

/** Android has no native editor — fall back to the shared Compose editor. */
actual fun presentNativeHomeCityEditor(
    onConfirm: (
        name: String,
        lat: Double,
        lng: Double,
        placeId: String?,
        done: (ok: Boolean, rateLimited: Boolean, nextAllowedAt: String?) -> Unit,
    ) -> Unit,
): Boolean = false
