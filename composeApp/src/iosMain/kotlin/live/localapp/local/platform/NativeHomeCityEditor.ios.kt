package live.localapp.local.platform

import live.localapp.local.HomeCityEditBridge

/**
 * iOS: wire the confirm handler into the bridge and ask Swift to present the
 * native editor. If Swift hasn't registered a presenter yet, return false so the
 * caller falls back to the Compose editor.
 */
actual fun presentNativeHomeCityEditor(
    onConfirm: (
        name: String,
        lat: Double,
        lng: Double,
        placeId: String?,
        done: (ok: Boolean, rateLimited: Boolean, nextAllowedAt: String?) -> Unit,
    ) -> Unit,
): Boolean {
    val present = HomeCityEditBridge.present ?: return false
    HomeCityEditBridge.onConfirm = onConfirm
    present()
    return true
}
