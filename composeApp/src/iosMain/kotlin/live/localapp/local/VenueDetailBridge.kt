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
        name: String, address: String?, city: String,
        lat: Double, lng: Double,
        photoUrl: String?, description: String?,
        category: String?, googleRating: Double?,
        website: String?, distanceMiles: Double?,
        upcomingEventCount: Int,
        upcomingEventsJson: String,
        reservationUrl: String?,
        cardX: Float, cardY: Float, cardW: Float, cardH: Float
    ) -> Unit)? = null

    /** Swift sets this. Compose calls it to dismiss the native venue detail screen. */
    var dismissVenueDetail: (() -> Unit)? = null

    /** Compose sets this. Swift calls it when the detail screen has been dismissed. */
    var onDismissed: (() -> Unit)? = null
}
