package live.localapp.local

/**
 * Bridge between Compose (Kotlin) and the native SwiftUI HomeCityEditView.
 *
 * Swift sets [present] (to show the native editor) during bridge setup. When the
 * user taps the home-city pencil, Compose stores its [onConfirm] handler and
 * calls [present]. The native view does the city search; on confirm it invokes
 * [onConfirm] with the picked city, and Kotlin runs the change_home_city RPC and
 * reports back through the `done` callback so Swift can show success / the
 * rate-limit date / an error.
 */
object HomeCityEditBridge {

    /** Swift sets this. Compose calls it to present the native editor. */
    var present: (() -> Unit)? = null

    /**
     * Compose sets this before presenting. Swift calls it on confirm with the
     * chosen city + a completion the native UI uses to render the outcome.
     */
    var onConfirm: ((
        name: String,
        lat: Double,
        lng: Double,
        placeId: String?,
        done: (ok: Boolean, rateLimited: Boolean, nextAllowedAt: String?) -> Unit,
    ) -> Unit)? = null
}
