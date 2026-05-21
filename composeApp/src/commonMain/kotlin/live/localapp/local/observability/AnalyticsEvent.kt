package live.localapp.local.observability

/**
 * Strongly-typed PostHog event. The taxonomy lives in the [Events] companion
 * so call sites read `Analytics.track(AnalyticsEvent.planCreated(...))` and
 * get auto-completion + compile-time name checking, without 50 data classes.
 *
 * Property naming: snake_case, past tense. Never put PII (email, name, phone)
 * in `properties` — use [Analytics.setUserProperty] for those if absolutely
 * needed, though the current rule is "skip user PII entirely."
 */
class AnalyticsEvent internal constructor(
    val name: String,
    val properties: Map<String, Any?>,
) {
    companion object Events {
        // ---- Lifecycle ----
        fun appOpened(coldStart: Boolean, secondsBackgrounded: Long? = null) =
            AnalyticsEvent("app_opened", nonNullMap(
                "cold_start" to coldStart,
                "seconds_backgrounded" to secondsBackgrounded,
            ))

        fun appBackgrounded() = AnalyticsEvent("app_backgrounded", emptyMap())

        fun appErrorShown(errorCode: String, surface: String) =
            AnalyticsEvent("app_error_shown", mapOf(
                "error_code" to errorCode,
                "surface" to surface,
            ))

        // ---- Auth ----
        fun signupStarted(entryPoint: String) =
            AnalyticsEvent("signup_started", mapOf("entry_point" to entryPoint))

        fun signedUp(method: String, entryPoint: String) =
            AnalyticsEvent("signed_up", mapOf(
                "method" to method,
                "entry_point" to entryPoint,
            ))

        fun signedIn(method: String) =
            AnalyticsEvent("signed_in", mapOf("method" to method))

        fun signedOut() = AnalyticsEvent("signed_out", emptyMap())

        fun signupAbandoned(lastStep: String) =
            AnalyticsEvent("signup_abandoned", mapOf("last_step" to lastStep))

        // ---- Discovery ----
        fun tabSwitched(fromTab: String, toTab: String) =
            AnalyticsEvent("tab_switched", mapOf(
                "from_tab" to fromTab,
                "to_tab" to toTab,
            ))

        fun feedViewed(feed: String, resultCount: Int) =
            AnalyticsEvent("feed_viewed", mapOf(
                "feed" to feed,
                "result_count" to resultCount,
            ))

        fun carouselScrolled(carouselId: String, feed: String, furthestIndex: Int) =
            AnalyticsEvent("carousel_scrolled", mapOf(
                "carousel_id" to carouselId,
                "feed" to feed,
                "furthest_index" to furthestIndex,
            ))

        fun eventViewed(
            eventId: String,
            source: String,
            positionInFeed: Int? = null,
            eventMetro: String? = null,
        ) = AnalyticsEvent("event_viewed", nonNullMap(
            "event_id" to eventId,
            "source" to source,
            "position_in_feed" to positionInFeed,
            "event_metro" to eventMetro,
        ))

        fun eventImageSwiped(eventId: String, furthestIndex: Int) =
            AnalyticsEvent("event_image_swiped", mapOf(
                "event_id" to eventId,
                "furthest_index" to furthestIndex,
            ))

        fun searchSubmitted(queryLength: Int, hasFilter: Boolean, resultCount: Int) =
            AnalyticsEvent("search_submitted", mapOf(
                "query_length" to queryLength,
                "has_filter" to hasFilter,
                "result_count" to resultCount,
            ))

        fun searchResultTapped(eventId: String, position: Int) =
            AnalyticsEvent("search_result_tapped", mapOf(
                "event_id" to eventId,
                "position" to position,
            ))

        fun filterApplied(filters: Map<String, Any?>) =
            AnalyticsEvent("filter_applied", mapOf("filters" to filters))

        // ---- Event actions ----
        fun eventSaved(eventId: String, source: String) =
            AnalyticsEvent("event_saved", mapOf(
                "event_id" to eventId,
                "source" to source,
            ))

        fun eventUnsaved(eventId: String) =
            AnalyticsEvent("event_unsaved", mapOf("event_id" to eventId))

        fun eventShareTapped(eventId: String, shareChannel: String) =
            AnalyticsEvent("event_share_tapped", mapOf(
                "event_id" to eventId,
                "share_channel" to shareChannel,
            ))

        fun shareLinkOpened(
            linkType: String,
            eventId: String? = null,
            planId: String? = null,
        ) = AnalyticsEvent("share_link_opened", nonNullMap(
            "link_type" to linkType,
            "event_id" to eventId,
            "plan_id" to planId,
        ))

        // ---- Plans ----
        fun planCreateStarted(entryPoint: String) =
            AnalyticsEvent("plan_create_started", mapOf("entry_point" to entryPoint))

        fun planCreated(
            planId: String,
            slotCount: Int,
            hasTitle: Boolean,
            creationMethod: String,
        ) = AnalyticsEvent("plan_created", mapOf(
            "plan_id" to planId,
            "slot_count" to slotCount,
            "has_title" to hasTitle,
            "creation_method" to creationMethod,
        ))

        fun planCreateAbandoned(lastStep: String) =
            AnalyticsEvent("plan_create_abandoned", mapOf("last_step" to lastStep))

        fun planViewed(
            planId: String,
            isCreator: Boolean,
            attendeeCount: Int,
            source: String,
        ) = AnalyticsEvent("plan_viewed", mapOf(
            "plan_id" to planId,
            "is_creator" to isCreator,
            "attendee_count" to attendeeCount,
            "source" to source,
        ))

        fun planEdited(planId: String, field: String) =
            AnalyticsEvent("plan_edited", mapOf(
                "plan_id" to planId,
                "field" to field,
            ))

        fun planDeleted(planId: String, hadAttendees: Boolean) =
            AnalyticsEvent("plan_deleted", mapOf(
                "plan_id" to planId,
                "had_attendees" to hadAttendees,
            ))

        fun planShared(planId: String, shareChannel: String) =
            AnalyticsEvent("plan_shared", mapOf(
                "plan_id" to planId,
                "share_channel" to shareChannel,
            ))

        fun planInviteAccepted(planId: String, wasSignedIn: Boolean) =
            AnalyticsEvent("plan_invite_accepted", mapOf(
                "plan_id" to planId,
                "was_signed_in" to wasSignedIn,
            ))

        fun planAttendanceChanged(planId: String, newStatus: String) =
            AnalyticsEvent("plan_attendance_changed", mapOf(
                "plan_id" to planId,
                "new_status" to newStatus,
            ))

        fun planSlotVoted(planId: String, slotId: String) =
            AnalyticsEvent("plan_slot_voted", mapOf(
                "plan_id" to planId,
                "slot_id" to slotId,
            ))

        // ---- Bo ----
        fun boOpened(entryPoint: String) =
            AnalyticsEvent("bo_opened", mapOf("entry_point" to entryPoint))

        fun boMessageSent(messageLength: Int, hasAttachedEvent: Boolean) =
            AnalyticsEvent("bo_message_sent", mapOf(
                "message_length" to messageLength,
                "has_attached_event" to hasAttachedEvent,
            ))

        fun boResponseReceived(
            responseMs: Long,
            hadWebSearch: Boolean,
            hadChips: Boolean,
            resultEventCount: Int,
        ) = AnalyticsEvent("bo_response_received", mapOf(
            "response_ms" to responseMs,
            "had_web_search" to hadWebSearch,
            "had_chips" to hadChips,
            "result_event_count" to resultEventCount,
        ))

        fun boResponseFailed(errorCode: String, phase: String) =
            AnalyticsEvent("bo_response_failed", mapOf(
                "error_code" to errorCode,
                "phase" to phase,
            ))

        fun boChipTapped(chipLabelHash: String) =
            AnalyticsEvent("bo_chip_tapped", mapOf("chip_label_hash" to chipLabelHash))

        fun boResultEventTapped(eventId: String, positionInResponse: Int) =
            AnalyticsEvent("bo_result_event_tapped", mapOf(
                "event_id" to eventId,
                "position_in_response" to positionInResponse,
            ))

        fun boPlanCreatedFromChat(planId: String, slotCount: Int) =
            AnalyticsEvent("bo_plan_created_from_chat", mapOf(
                "plan_id" to planId,
                "slot_count" to slotCount,
            ))

        fun boThreadCleared() = AnalyticsEvent("bo_thread_cleared", emptyMap())

        // ---- Onboarding ----
        fun onboardingStepViewed(stepName: String, stepIndex: Int) =
            AnalyticsEvent("onboarding_step_viewed", mapOf(
                "step_name" to stepName,
                "step_index" to stepIndex,
            ))

        fun onboardingCompleted(totalSeconds: Long, notificationsGranted: Boolean) =
            AnalyticsEvent("onboarding_completed", mapOf(
                "total_seconds" to totalSeconds,
                "notifications_granted" to notificationsGranted,
            ))

        fun onboardingAbandoned(lastStep: String) =
            AnalyticsEvent("onboarding_abandoned", mapOf("last_step" to lastStep))

        // ---- Monetization (placeholders) ----
        fun paywallViewed(trigger: String, surface: String) =
            AnalyticsEvent("paywall_viewed", mapOf(
                "trigger" to trigger,
                "surface" to surface,
            ))

        fun paywallDismissed(secondsVisible: Long) =
            AnalyticsEvent("paywall_dismissed", mapOf("seconds_visible" to secondsVisible))

        // ---- Escape hatch ----
        // Use sparingly. If you find yourself reaching for it, the event
        // probably belongs in the taxonomy above.
        fun custom(name: String, properties: Map<String, Any?> = emptyMap()) =
            AnalyticsEvent(name, properties)

        private fun nonNullMap(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
            pairs.filter { it.second != null }.toMap()
    }
}
