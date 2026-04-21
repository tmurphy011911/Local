package live.localapp.local

/**
 * Bridge between Compose (Kotlin) and the native SwiftUI VenueDetailView.
 *
 * When a venue card is tapped on iOS, Compose calls [showVenueDetail] with all venue
 * data + the card frame (in points). Swift presents the native detail screen
 * with an Airbnb-style transition. When the user dismisses, Swift calls
 * [onDismissed] so Compose can clean up state.
 */
object VenueDetailBridge {

    /**
     * Swift sets this. Compose calls it to present the native venue detail screen.
     * Parameters: all venue fields + card frame (x, y, w, h in points).
     */
    var showVenueDetail: ((
        id: String,
        name: String, address: String?, city: String,
        lat: Double, lng: Double,
        photoUrl: String?, description: String?,
        category: String?, googleRating: Double?,
        website: String?, distanceMiles: Double?,
        upcomingEventCount: Int,
        upcomingEventsJson: String,
        reservationUrl: String?,
        isFavorited: Boolean,
        cardX: Float, cardY: Float, cardW: Float, cardH: Float
    ) -> Unit)? = null

    /**
     * Swift sets this. Compose calls it AFTER the detail has already been presented,
     * with the freshly-fetched upcoming events. Used to replace the skeleton state.
     * Parameters: venue id (for sanity check), events JSON (same shape as show call).
     */
    var updateVenueEvents: ((id: String, upcomingEventsJson: String) -> Unit)? = null

    /** Swift sets this. Compose calls it to dismiss the native venue detail screen. */
    var dismissVenueDetail: (() -> Unit)? = null

    /** Compose sets this. Swift calls it when the detail screen has been dismissed. */
    var onDismissed: (() -> Unit)? = null

    /** Compose sets this. Swift calls it when the user taps the heart on the native detail. */
    var onFavorited: ((String) -> Unit)? = null
}
