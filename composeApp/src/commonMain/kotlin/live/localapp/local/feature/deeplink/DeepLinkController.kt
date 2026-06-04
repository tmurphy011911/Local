package live.localapp.local.feature.deeplink

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.Venue
import live.localapp.local.domain.repository.EventRepository
import live.localapp.local.domain.repository.PlanRepository
import live.localapp.local.domain.repository.VenueRepository
import live.localapp.local.observability.Analytics
import live.localapp.local.observability.AnalyticsEvent
import live.localapp.local.platform.checkClipboardForDeepLink
import live.localapp.local.platform.checkInstallReferrerForDeepLink
import live.localapp.local.platform.clearClipboardDeepLink
import live.localapp.local.platform.setDeepLinkCallback

/** A resolved shared link, ready to present. */
sealed class DeepLinkContent {
    data class SharedPlan(val plan: Plan) : DeepLinkContent()
    data class SharedEvent(val event: Event) : DeepLinkContent()
    data class SharedVenue(val venue: Venue) : DeepLinkContent()
}

/**
 * Single owner of deep-link resolution for the whole app.
 *
 * ## Why this exists
 *
 * Deep links can arrive two ways and at any moment:
 *
 *  - **Deferred** — a fresh install opened from a share link. Android delivers
 *    it through the Play Install Referrer; iOS leaves it on the clipboard. We
 *    check both once at startup.
 *  - **Runtime** — a Universal Link / App Link while the process is alive,
 *    delivered through [setDeepLinkCallback] (iOS `onOpenURL`, Android
 *    `onNewIntent`).
 *
 * Previously **two** composables each registered their own
 * [setDeepLinkCallback] (App.kt and AppNavigation.kt) and each ran their own
 * deferred check. The platform bridge keeps a single callback slot, so whoever
 * composed last silently clobbered the other — which is why links "rarely
 * brought you to the right page." Both also raced to consume the one-shot
 * clipboard/referrer value.
 *
 * This controller is the *single* registrant and the *single* deferred reader.
 * It exposes the resolved link as [pending]; UI layers observe that flow and
 * decide how to present it (logged-out preview in App.kt, logged-in overlay in
 * AppNavigation.kt). Exactly one of those is ever composed at a time, so there
 * is no double-handling. Whoever shows the link calls [consume].
 *
 * Resolution (the network fetch turning a path into a typed payload) happens
 * here too, so it runs once regardless of which UI layer is mounted.
 */
class DeepLinkController(
    private val planRepo: PlanRepository,
    private val eventRepo: EventRepository,
    private val venueRepo: VenueRepository,
) {
    // Lives for the whole process — the controller is a Koin singleton with no
    // lifecycle of its own. A late Universal Link can fire long after any one
    // screen's coroutine scope is gone, so resolution must not be tied to one.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _pending = MutableStateFlow<DeepLinkContent?>(null)
    val pending: StateFlow<DeepLinkContent?> = _pending.asStateFlow()

    private var started = false

    /**
     * Register the single runtime callback and run the one-shot deferred check.
     * Idempotent — safe to call from App's top-level on every composition.
     */
    fun start() {
        if (started) return
        started = true

        // Runtime Universal Links / App Links.
        setDeepLinkCallback { path -> scope.launch { resolve(path) } }

        // Deferred link from a fresh install. One-shot: clear the clipboard once
        // we've successfully turned it into a payload so we don't re-open it.
        scope.launch {
            val deferred = checkInstallReferrerForDeepLink() ?: checkClipboardForDeepLink()
            if (deferred != null && resolve(deferred)) {
                clearClipboardDeepLink()
            }
        }
    }

    /** Clear the pending link once a screen has taken ownership of presenting it. */
    fun consume() {
        _pending.value = null
    }

    /**
     * Fetch the payload for [path] and publish it on [pending].
     * @return true if the path matched a known prefix and resolved to content.
     */
    private suspend fun resolve(path: String): Boolean {
        return try {
            when {
                path.startsWith("/p/") -> {
                    val plan = planRepo.getSharedPlan(path.removePrefix("/p/")) ?: return false
                    _pending.value = DeepLinkContent.SharedPlan(plan)
                    Analytics.track(AnalyticsEvent.shareLinkOpened(linkType = "plan", planId = plan.id))
                    true
                }
                path.startsWith("/e/") -> {
                    val event = eventRepo.getEvent(path.removePrefix("/e/")) ?: return false
                    _pending.value = DeepLinkContent.SharedEvent(event)
                    Analytics.track(AnalyticsEvent.shareLinkOpened(linkType = "event", eventId = event.id))
                    true
                }
                path.startsWith("/v/") -> {
                    val venue = venueRepo.getVenuesByIds(listOf(path.removePrefix("/v/"))).firstOrNull()
                        ?: return false
                    _pending.value = DeepLinkContent.SharedVenue(venue)
                    Analytics.track(AnalyticsEvent.shareLinkOpened(linkType = "venue"))
                    true
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }
}
