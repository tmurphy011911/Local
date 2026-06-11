package live.localapp.local.ui.chat

import com.russhwolf.settings.Settings
import live.localapp.local.domain.model.StatusTier

/** Which surface created a plan — gating is counted independently per source. */
enum class PlanSource { YOCAL, MANUAL }

/**
 * Manages premium gating state for Yocal chat / plan creation.
 *
 * Flow:
 * - Chat is always free
 * - First plan from Yocal is free; first plan from the manual builder is free
 * - The second save on either surface triggers the paywall
 * - Premium users bypass all gates
 */
class PremiumGate {

    private val settings = Settings()

    /** Total number of plans created across all sources (for analytics/debug). */
    var plansCreated: Int
        get() = settings.getInt(KEY_PLANS_CREATED, 0)
        private set(value) = settings.putInt(KEY_PLANS_CREATED, value)

    /** Plans saved from the Yocal chat. */
    var yocalPlansCreated: Int
        get() = settings.getInt(KEY_YOCAL_PLANS_CREATED, 0)
        private set(value) = settings.putInt(KEY_YOCAL_PLANS_CREATED, value)

    /** Plans saved from the manual build-a-plan flow. */
    var manualPlansCreated: Int
        get() = settings.getInt(KEY_MANUAL_PLANS_CREATED, 0)
        private set(value) = settings.putInt(KEY_MANUAL_PLANS_CREATED, value)

    /** Whether the user has an active paid subscription (from StoreKit/RevenueCat) */
    var hasPaidSubscription: Boolean
        get() = settings.getBoolean(KEY_HAS_PAID_SUB, false)
        set(value) = settings.putBoolean(KEY_HAS_PAID_SUB, value)

    /** Paid subscription expiry as ISO date string (e.g. "2027-01-31"). Set by StoreKit/RevenueCat. */
    var paidSubscriptionExpiry: String
        get() = settings.getString(KEY_PAID_SUB_EXPIRY, "")
        set(value) = settings.putString(KEY_PAID_SUB_EXPIRY, value)

    /**
     * The user id the cached [hasPaidSubscription] belongs to. Lets the launch
     * sync wipe the cached paid status only on an actual account *change* (not on
     * every same-user relaunch) — otherwise a paying customer would momentarily
     * read hasPaidSubscription=false during the async RevenueCat round-trip and
     * get paywalled on launch. Empty when never synced.
     */
    var paidSubscriptionUserId: String
        get() = settings.getString(KEY_PAID_SUB_USER_ID, "")
        set(value) = settings.putString(KEY_PAID_SUB_USER_ID, value)

    /**
     * Server-owned Local Pro trial end as an ISO-8601 instant, mirrored from
     * `user_profiles.trial_ends_at` by UserPreferencesRepositoryImpl. Empty when
     * there is no trial on record. Key must stay in sync with the writer there.
     */
    var trialEndsAt: String
        get() = settings.getString(KEY_TRIAL_ENDS_AT, "")
        set(value) = settings.putString(KEY_TRIAL_ENDS_AT, value)

    /** Whether the server-granted free trial is still active (end instant in the future). */
    val isTrialActive: Boolean
        get() {
            val raw = trialEndsAt
            if (raw.isBlank()) return false
            val endMs = try {
                kotlinx.datetime.Instant.parse(raw).toEpochMilliseconds()
            } catch (_: Exception) {
                return false
            }
            return endMs > kotlin.time.Clock.System.now().toEpochMilliseconds()
        }

    /** Track that the user has been shown the paywall */
    var hasSeenPaywall: Boolean
        get() = settings.getBoolean(KEY_HAS_SEEN_PAYWALL, false)
        set(value) = settings.putBoolean(KEY_HAS_SEEN_PAYWALL, value)

    /**
     * ISO local date (yyyy-MM-dd) on which the user last dismissed the home
     * trial-countdown banner, or empty if never. Dismissal only suppresses the
     * banner for that calendar day — it re-appears each new day. Only honored
     * while >= 7 days remain; under 7 days the banner is non-dismissible and
     * ignores this value.
     */
    var trialBannerDismissedOn: String
        get() = settings.getString(KEY_TRIAL_BANNER_DISMISSED_ON, "")
        set(value) = settings.putString(KEY_TRIAL_BANNER_DISMISSED_ON, value)

    /** Whether the user has seen the paywall and is still not premium */
    val hasDeclinedPaywall: Boolean
        get() = hasSeenPaywall && !isPremium

    /**
     * Whether the user currently has premium access — either a paid RevenueCat
     * subscription OR an active server-granted free trial. The trial promises
     * "full access to Local Pro", so it must bypass the paywall just like a paid sub.
     */
    val isPremium: Boolean
        get() = hasPaidSubscription || isTrialActive

    /** Record that a plan was created from [source]. Increments the total + the per-source counter. */
    fun recordPlanCreated(source: PlanSource) {
        plansCreated += 1
        when (source) {
            PlanSource.YOCAL -> yocalPlansCreated += 1
            PlanSource.MANUAL -> manualPlansCreated += 1
        }
    }

    /**
     * Whether the user can save a plan from [source] without hitting the paywall.
     *
     * Each surface gets its own free save — the first Yocal plan is free AND the
     * first manual plan is free, independently. The second save on either
     * surface triggers the paywall.
     */
    fun canCreatePlan(source: PlanSource): Boolean {
        if (isPremium) return true
        val used = when (source) {
            PlanSource.YOCAL -> yocalPlansCreated
            PlanSource.MANUAL -> manualPlansCreated
        }
        println("PremiumGate: canCreatePlan? source=$source used=$used isPremium=$isPremium")
        return used < 1
    }

    /** Get the reward description for a tier (for display). */
    fun rewardForTier(tier: StatusTier): String? = when (tier) {
        StatusTier.INSIDER -> "$10 Ticketmaster Gift Card"
        StatusTier.TOWNIE -> "$25 Ticketmaster Gift Card"
        StatusTier.LOCAL -> "$250 Ticketmaster Gift Card"
        StatusTier.LOCAL_LEGEND -> "$500 Ticketmaster Gift Card"
        else -> null
    }

    /** Clear all state (for logout) */
    fun clear() {
        settings.remove(KEY_PLANS_CREATED)
        settings.remove(KEY_YOCAL_PLANS_CREATED)
        settings.remove(KEY_MANUAL_PLANS_CREATED)
        settings.remove(KEY_HAS_PAID_SUB)
        settings.remove(KEY_PAID_SUB_EXPIRY)
        settings.remove(KEY_PAID_SUB_USER_ID)
        // Legacy reward keys — remove if present from older builds.
        settings.remove(KEY_REWARD_EXPIRY)
        settings.remove(KEY_LAST_REWARDED_TIER)
        settings.remove(KEY_HAS_SEEN_PAYWALL)
        settings.remove(KEY_TRIAL_BANNER_DISMISSED_ON)
        settings.remove(KEY_TRIAL_ENDS_AT)
    }

    companion object {
        private const val KEY_PLANS_CREATED = "yocal_plans_created"
        private const val KEY_YOCAL_PLANS_CREATED = "yocal_plans_created_yocal"
        private const val KEY_MANUAL_PLANS_CREATED = "yocal_plans_created_manual"
        private const val KEY_HAS_PAID_SUB = "yocal_has_paid_sub"
        private const val KEY_PAID_SUB_EXPIRY = "yocal_paid_sub_expiry"
        private const val KEY_PAID_SUB_USER_ID = "yocal_paid_sub_user_id"
        private const val KEY_REWARD_EXPIRY = "yocal_reward_expiry"
        private const val KEY_LAST_REWARDED_TIER = "yocal_last_rewarded_tier"
        private const val KEY_HAS_SEEN_PAYWALL = "yocal_has_seen_paywall"
        private const val KEY_TRIAL_BANNER_DISMISSED_ON = "local_trial_banner_dismissed_on"
        // Written by UserPreferencesRepositoryImpl.loadPreferences — keep the literal in sync.
        const val KEY_TRIAL_ENDS_AT = "local_trial_ends_at"
    }
}
