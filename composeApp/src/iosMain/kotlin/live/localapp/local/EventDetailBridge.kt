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
     *
     * [venueImageUrl] is the joined venue's photo, used by Swift as a last-resort
     * fallback for the hero when [imageUrl] is null.
     */
    var showEventDetail: ((
        id: String, title: String, description: String?, venueDescription: String?,
        imageUrl: String?, venueImageUrl: String?,
        startAt: String?, venueName: String?, venueAddress: String?,
        venueCity: String, venueLat: Double, venueLng: Double,
        isFree: Boolean, priceDisplay: String, ticketUrl: String?, sourceUrl: String?,
        category: String, subcategory: String?,
        source: String?,
        cardX: Float, cardY: Float, cardW: Float, cardH: Float, cardImageUrl: String?,
        isFavorited: Boolean,
        timeKnown: Boolean,
        /** Distance to the venue in miles; negative when unknown. Drives the venue card's secondary line. */
        venueDistanceMiles: Double,
    ) -> Unit)? = null

    /** Swift sets this. Compose calls it to dismiss the native detail screen. */
    var dismissEventDetail: (() -> Unit)? = null

    /**
     * Swift sets this. Compose calls it AFTER the detail is presented with the
     * venue's upcoming-event count (resolved async by name+city, since events
     * carry no venue FK). Used to fill the "N events" part of the venue card.
     */
    var updateVenueEventCount: ((eventId: String, count: Int) -> Unit)? = null

    /** Compose sets this. Swift calls it when the user taps the venue card to open the venue page. */
    var onVenueTap: ((venueName: String, city: String) -> Unit)? = null

    /** Compose sets this. Swift calls it when the detail screen has been dismissed. */
    var onDismissed: (() -> Unit)? = null

    /** Compose sets this. Swift calls it when the user favorites an event. */
    var onFavorited: ((String) -> Unit)? = null

    /** Whether to show directions/ride CTAs (true when opened from Plans). */
    var showDirections: Boolean = false
}
