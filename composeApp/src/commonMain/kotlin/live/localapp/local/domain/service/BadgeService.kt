package live.localapp.local.domain.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import live.localapp.local.domain.model.BadgeAction
import live.localapp.local.domain.model.BadgeMilestone
import live.localapp.local.domain.model.thresholdsFor
import live.localapp.local.domain.model.StatusTier
import live.localapp.local.domain.model.pointsForMilestone
import live.localapp.local.domain.model.statusTierForPoints
import live.localapp.local.domain.repository.BadgeRepository
import live.localapp.local.domain.repository.UserPreferencesRepository

/** Events emitted when a badge milestone or status change occurs. */
sealed interface BadgeCelebration {
    data class MilestoneEarned(
        val action: BadgeAction,
        val milestone: Int,
        val points: Int,
        val state: String,
    ) : BadgeCelebration

    data class StatusLevelUp(
        val previousTier: StatusTier,
        val newTier: StatusTier,
        val totalPoints: Int,
    ) : BadgeCelebration

    data class NewState(
        val state: String,
    ) : BadgeCelebration
}

/**
 * Lightweight service that checks and awards badge milestones.
 * Injected into any ViewModel that performs trackable actions.
 * Emits celebration events via SharedFlow for the UI to observe.
 */
class BadgeService(
    private val badgeRepository: BadgeRepository,
    private val userPrefsRepo: UserPreferencesRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _celebrations = MutableSharedFlow<BadgeCelebration>(extraBufferCapacity = 5)
    val celebrations: SharedFlow<BadgeCelebration> = _celebrations.asSharedFlow()

    /** Emitted on every action so profile can refresh counts even without a milestone. */
    private val _dataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 5)
    val dataChanged: SharedFlow<Unit> = _dataChanged.asSharedFlow()

    /** Known states the user has milestones in (cached to detect new states). */
    private val knownStates = mutableSetOf<String>()
    private var totalPointsCache = 0
    private var initialized = false

    /** Local, per-device watermark for creator-badge toasts. Multiplatform-
     *  settings' no-arg factory points at the same shared store the prefs repo
     *  uses, so this reads/writes the platform default store. */
    private val settings = com.russhwolf.settings.Settings()

    /** Action types the server (accept_plan_invite RPC) awards to the plan
     *  creator. The client no longer awards these — it only surfaces the toast. */
    private val creatorBadgeActions = setOf("invite_accepted", "group_5_confirmed", "group_10_confirmed")

    private fun watermarkKey(userId: String) = "creator_badge_seen_through_$userId"

    /** On a device's FIRST sight of the creator badges, we must not retro-toast
     *  the user's whole back-catalogue — but we MUST still toast an accept that
     *  just happened while they're using the app (the common case, and the whole
     *  point of the creator toast). So the first-sight baseline is set to the
     *  newest badge earned *before* this window; anything newer is treated as
     *  live and toasted. Widening this only risks a benign duplicate celebration
     *  after a reinstall; narrowing it risks silently eating a real accept — so
     *  we err generous. */
    private val FIRST_SIGHT_RECENT_WINDOW = 6.hours

    /** Newest creator badge that is clearly historical (earned before
     *  [FIRST_SIGHT_RECENT_WINDOW] ago) — the correct first-sight watermark.
     *  Null when there is no such badge (brand-new creator, or every creator
     *  badge is within the window), in which case the caller baselines to
     *  DISTANT_PAST so the recent ones toast. */
    private fun firstSightWatermark(badges: List<BadgeMilestone>): kotlin.time.Instant? {
        val cutoff = kotlin.time.Clock.System.now() - FIRST_SIGHT_RECENT_WINDOW
        return badges
            .filter { it.actionType in creatorBadgeActions }
            .mapNotNull { parseTs(it.earnedAt) }
            .filter { it < cutoff }
            .maxOrNull()
    }

    private fun creatorAction(actionType: String): BadgeAction? = when (actionType) {
        "invite_accepted" -> BadgeAction.INVITE_ACCEPTED
        "group_5_confirmed" -> BadgeAction.GROUP_5_CONFIRMED
        "group_10_confirmed" -> BadgeAction.GROUP_10_CONFIRMED
        else -> null
    }

    /** Parse a DB timestamptz string to an Instant. Never compare these ISO
     *  strings lexicographically — variable fractional-second width makes that
     *  unreliable (see project_gotchas). */
    private fun parseTs(s: String?): kotlin.time.Instant? =
        if (s.isNullOrBlank()) null
        else try { kotlin.time.Instant.parse(s) } catch (_: Throwable) { null }

    /** Initialize cache from DB. Call once on app start or profile load. */
    fun initialize() {
        if (initialized) return
        scope.launch {
            val userId = userPrefsRepo.getLocalUserId() ?: return@launch
            try {
                val milestones = badgeRepository.getMilestones(userId)
                knownStates.addAll(milestones.map { it.state })
                // Deduplicate milestones by action+milestone to avoid double-counting across states
                val uniqueMilestones = milestones.distinctBy { "${it.actionType}_${it.milestone}" }
                totalPointsCache = uniqueMilestones.sumOf { it.points }

                // Baseline the creator-badge toast watermark on first run so the
                // server's already-awarded historical badges don't retro-toast.
                // Baseline to the newest *historical* badge only — an accept that
                // just landed (within the recent window) is left unbaselined so
                // claimCreatorBadges still toasts it. (This runs from
                // ProgressViewModel, which may not have loaded before the accept;
                // claimCreatorBadges applies the same rule so the toast is robust
                // to that ordering.)
                val wmKey = watermarkKey(userId)
                if (settings.getStringOrNull(wmKey) == null) {
                    settings.putString(wmKey, firstSightWatermark(milestones)?.toString() ?: "")
                }
                initialized = true
            } catch (_: Throwable) {}
        }
    }

    /**
     * Record an action and check for new milestones.
     * @param action The type of action performed
     * @param state The US state abbreviation (e.g. "MA")
     * @param newCount The updated total count for this action in this state
     *
     * Fire-and-forget — body runs on [scope]. For multi-action sequences where
     * order and the [totalPointsCache] read/write must be serialized, use a
     * single coroutine that calls [recordActionImpl] in series (see
     * [claimCreatorBadges]).
     */
    fun recordAction(action: BadgeAction, state: String, newCount: Int = 0) {
        val userId = userPrefsRepo.getLocalUserId() ?: return
        scope.launch { recordActionImpl(userId, action, state, newCount) }
    }

    /**
     * Suspending core of [recordAction]. Extracted so multiple actions can be
     * processed sequentially without racing on [totalPointsCache] or interleaving
     * celebration emissions in non-deterministic order.
     *
     * Updates the lifetime counter in `user_action_counts` first, then uses
     * the resulting count for milestone checks. The counter is monotonic — it
     * never decreases when the user unsaves an event or deletes a plan — so
     * Profile-screen counts stay stable across undo actions.
     */
    private suspend fun recordActionImpl(
        userId: String,
        action: BadgeAction,
        state: String,
        newCount: Int,
    ) {
        val previousTotal = totalPointsCache
        val previousTier = statusTierForPoints(previousTotal)
        val actionKey = action.name.lowercase()

        // Get existing milestones for this action (across all states)
        val existing = try {
            badgeRepository.getMilestones(userId)
                .filter { it.actionType == actionKey }
                .map { it.milestone }
                .toSet()
        } catch (_: Throwable) { emptySet() }

        // Update the lifetime counter and read back the new value.
        //   newCount == 0 → per-action call (EVENT_SAVED, EVENT_SHARED,
        //                   PLAN_CREATED) — increment by 1.
        //   newCount  > 0 → snapshot call (INVITE_ACCEPTED, GROUP_*_CONFIRMED
        //                   via claimCreatorBadges) — ratchet up to the
        //                   snapshot, never down.
        val actualCount = if (newCount > 0) {
            badgeRepository.bumpActionCountTo(userId, actionKey, newCount)
        } else {
            badgeRepository.incrementActionCount(userId, actionKey)
        }

        var earnedPoints = 0
        var latestMilestone: Int? = null

        for (threshold in thresholdsFor(action)) {
            if (actualCount >= threshold && threshold !in existing) {
                val pts = pointsForMilestone(action, threshold)
                badgeRepository.awardMilestone(
                    userId = userId,
                    state = state,
                    actionType = actionKey,
                    milestone = threshold,
                    points = pts,
                )
                earnedPoints += pts
                latestMilestone = threshold
            }
        }

        if (latestMilestone != null) {
            totalPointsCache += earnedPoints

            _celebrations.emit(
                BadgeCelebration.MilestoneEarned(
                    action = action,
                    milestone = latestMilestone,
                    points = earnedPoints,
                    state = state,
                )
            )

            // Check for level up
            val newTier = statusTierForPoints(totalPointsCache)
            if (newTier != previousTier) {
                _celebrations.emit(
                    BadgeCelebration.StatusLevelUp(
                        previousTier = previousTier,
                        newTier = newTier,
                        totalPoints = totalPointsCache,
                    )
                )
            }
        }

        // Always notify so profile refreshes counts
        _dataChanged.emit(Unit)
    }

    /**
     * Surface a toast for any creator-side FOMO the server has awarded since we
     * last looked. Call this when the creator opens a plan they own.
     *
     * The award itself lives entirely server-side now: `accept_plan_invite`
     * (SECURITY DEFINER) writes the creator's `user_badges` rows on each accept
     * (+2500 base, +5000 at 5 guests, +10000 at 10), and the `sync_fomo_total`
     * trigger credits `user_profiles.fomo_total` instantly — no dependency on
     * the creator opening the app. This method therefore does NOT award; it
     * only:
     *   1. Ratchets the Profile-screen lifetime counters (`user_action_counts`)
     *      up to the live snapshot, so progress rows stay in sync. This touches
     *      only the counter table, never `user_badges`.
     *   2. Emits a [BadgeCelebration.MilestoneEarned] for each creator badge
     *      newer than the per-device watermark, so the global celebration
     *      overlay toasts it. `totalPointsCache` is re-summed authoritatively
     *      from the DB (not incremented) so restarts can't double-count.
     *
     * Idempotent: re-opening the same plan after the toast fired is a no-op —
     * the watermark already covers those badges.
     */
    fun claimCreatorBadges() {
        val userId = userPrefsRepo.getLocalUserId() ?: return
        scope.launch {
            // 1. Keep Profile counters fresh (counter table only — no award).
            try {
                val snapshot = badgeRepository.getCreatorAcceptSnapshot(userId)
                if (snapshot.totalAccepts > 0)
                    badgeRepository.bumpActionCountTo(userId, "invite_accepted", snapshot.totalAccepts)
                if (snapshot.qualifiesForGroup5)
                    badgeRepository.bumpActionCountTo(userId, "group_5_confirmed", 1)
                if (snapshot.qualifiesForGroup10)
                    badgeRepository.bumpActionCountTo(userId, "group_10_confirmed", 1)
            } catch (_: Throwable) {}

            // 2. Toast newly-arrived creator badges.
            val all = try { badgeRepository.getMilestones(userId) } catch (_: Throwable) { return@launch }
            val creatorBadges = all.filter { it.actionType in creatorBadgeActions }

            val wmKey = watermarkKey(userId)
            val watermark = settings.getStringOrNull(wmKey)
            // First sight on this device: baseline to the newest *historical*
            // badge (older than the recent window) rather than the newest badge
            // overall. That keeps the back-catalogue from retro-toasting while
            // still letting an accept that just happened fall through and toast —
            // otherwise the creator's very first accept on a fresh install (before
            // ProgressViewModel's initialize() has run) is silently swallowed.
            // We do NOT return early; we fall through to the fresh-badge check
            // below, which advances the watermark to whatever it toasts.
            val wmTs = if (watermark == null) {
                firstSightWatermark(creatorBadges) ?: kotlin.time.Instant.DISTANT_PAST
            } else {
                parseTs(watermark) ?: kotlin.time.Instant.DISTANT_PAST
            }
            val fresh = creatorBadges
                .mapNotNull { b -> parseTs(b.earnedAt)?.let { it to b } }
                .filter { it.first > wmTs }
                .sortedBy { it.first }
                .map { it.second }
            if (fresh.isEmpty()) return@launch

            val previousTier = statusTierForPoints(totalPointsCache)
            for (b in fresh) {
                val action = creatorAction(b.actionType) ?: continue
                _celebrations.emit(
                    BadgeCelebration.MilestoneEarned(
                        action = action,
                        milestone = b.milestone,
                        points = b.points,
                        state = b.state,
                    )
                )
            }

            // Re-sum authoritatively (matches initialize()'s dedup) so the cache
            // is the true total regardless of when badges were written.
            totalPointsCache = all.distinctBy { "${it.actionType}_${it.milestone}" }.sumOf { it.points }
            val newTier = statusTierForPoints(totalPointsCache)
            if (newTier != previousTier) {
                _celebrations.emit(
                    BadgeCelebration.StatusLevelUp(previousTier, newTier, totalPointsCache)
                )
            }

            settings.putString(
                wmKey,
                fresh.maxByOrNull { parseTs(it.earnedAt) ?: kotlin.time.Instant.DISTANT_PAST }!!.earnedAt,
            )
            _dataChanged.emit(Unit)
        }
    }
}
