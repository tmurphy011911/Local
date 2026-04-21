package live.localapp.local

/**
 * Bridge between Compose (Kotlin) and the native SwiftUI EventDetailView.
 *
 * When a card is tapped on iOS, Compose calls [showEventDetail] with all event
 * data + the card frame (in points). Swift presents the native detail screen
 * with an Airbnb-style transition. When the user dismisses, Swift calls
 * [onDismissed] so Compose can clean up state.
 */
object EventDetailBridge {

    /**
     * Swift sets this. Compose calls it to present the native detail screen.
     * Parameters: all event fields + card frame (x, y, w, h in points) + card image URL.
     */
    var showEventDetail: ((
        id: String, title: String, description: String?, venueDescription: String?,
        imageUrl: String?, startAt: String?, venueName: String?, venueAddress: String?,
        venueCity: String, venueLat: Double, venueLng: Double,
        isFree: Boolean, priceDisplay: String, ticketUrl: String?, sourceUrl: String?,
        category: String, subcategory: String?,
        source: String?,
        cardX: Float, cardY: Float, cardW: Float, cardH: Float, cardImageUrl: String?,
        isFavorited: Boolean,
    ) -> Unit)? = null

    /** Swift sets this. Compose calls it to dismiss the native detail screen. */
    var dismissEventDetail: (() -> Unit)? = null

    /** Compose sets this. Swift calls it when the detail screen has been dismissed. */
    var onDismissed: (() -> Unit)? = null

    /** Compose sets this. Swift calls it when the user favorites an event. */
    var onFavorited: ((String) -> Unit)? = null

    /** Whether to show directions/ride CTAs (true when opened from Plans). */
    var showDirections: Boolean = false
}
