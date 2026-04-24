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
 * - Tier rewards grant free Pro for a duration (checked on launch)
 */
class PremiumGate {

    private val settings = Settings()

    /** Whether the user has seen the tutorial */
    var tutorialSeen: Boolean
        get() = settings.getBoolean(KEY_TUTORIAL_SEEN, false)
        set(value) = settings.putBoolean(KEY_TUTORIAL_SEEN, value)

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

    /** Premium reward expiry as ISO date string (e.g. "2026-07-07") */
    private var rewardExpiryDate: String
        get() = settings.getString(KEY_REWARD_EXPIRY, "")
        set(value) = settings.putString(KEY_REWARD_EXPIRY, value)

    /** The tier that last granted a reward (to avoid re-granting) */
    private var lastRewardedTier: String
        get() = settings.getString(KEY_LAST_REWARDED_TIER, "")
        set(value) = settings.putString(KEY_LAST_REWARDED_TIER, value)

    /** Track that the user has been shown the paywall */
    var hasSeenPaywall: Boolean
        get() = settings.getBoolean(KEY_HAS_SEEN_PAYWALL, false)
        set(value) = settings.putBoolean(KEY_HAS_SEEN_PAYWALL, value)

    /** Whether the user has seen the paywall and is still not premium */
    val hasDeclinedPaywall: Boolean
        get() = hasSeenPaywall && !isPremium

    /** Whether the user currently has premium access (paid OR reward) */
    val isPremium: Boolean
        get() {
            if (hasPaidSubscription) return true
            val expiry = rewardExpiryDate
            if (expiry.isBlank()) return false
            val today = kotlin.time.Clock.System.now().toString().take(10)
            return today <= expiry
        }

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
     * surface triggers the paywall. Tier-reward free months still bypass via
     * `isPremium` → `rewardExpiryDate`.
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

    /** Whether the tutorial should show (first Yocal open) */
    fun shouldShowTutorial(): Boolean = !tutorialSeen

    /**
     * Check if a tier upgrade should grant a free Pro reward.
     * Call when the user's tier changes.
     *
     * Returns the reward description if newly granted, null otherwise.
     */
    fun checkTierReward(newTier: StatusTier): String? {
        // Don't re-grant for the same tier
        if (newTier.name == lastRewardedTier) return null

        val rewardMonths = tierRewardMonths(newTier) ?: return null

        // Grant the reward — extend from the latest of: today, paid sub expiry, current reward expiry
        val today = kotlin.time.Clock.System.now().toString().take(10)
        val paidExpiry = paidSubscriptionExpiry
        val currentReward = rewardExpiryDate

        // Find the latest date to extend from
        val startFrom = listOf(today, paidExpiry, currentReward)
            .filter { it.isNotBlank() }
            .maxOrNull() ?: today

        val parts = startFrom.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null

        // Calculate expiry: add months from the start date
        var newMonth = month + rewardMonths
        var newYear = year
        while (newMonth > 12) {
            newMonth -= 12
            newYear++
        }
        val newDay = day.coerceAtMost(28) // avoid invalid dates
        val effectiveExpiry = "$newYear-${newMonth.toString().padStart(2, '0')}-${newDay.toString().padStart(2, '0')}"

        rewardExpiryDate = effectiveExpiry
        lastRewardedTier = newTier.name
        println("PremiumGate: tier reward granted! tier=${newTier.name} months=$rewardMonths expiry=$effectiveExpiry")

        return when (newTier) {
            StatusTier.REGULAR -> "1 month of Local Pro free!"
            StatusTier.INSIDER -> "2 months of Local Pro free!"
            StatusTier.TOWNIE -> "\$25 Ticketmaster Gift Card!"
            StatusTier.LOCAL -> "\$250 Ticketmaster Gift Card!"
            StatusTier.LOCAL_LEGEND -> "\$500 Ticketmaster Gift Card!"
            else -> null
        }
    }

    /** How many months of free Pro a tier grants. */
    private fun tierRewardMonths(tier: StatusTier): Int? = when (tier) {
        StatusTier.REGULAR -> 1
        StatusTier.INSIDER -> 2
        else -> null // Townie, Local, and Local Legend are gift cards, not free months
    }

    /** Get the reward description for a tier (for display). */
    fun rewardForTier(tier: StatusTier): String? = when (tier) {
        StatusTier.REGULAR -> "1 month Local Pro free"
        StatusTier.INSIDER -> "2 months Local Pro free"
        StatusTier.TOWNIE -> "$25 Ticketmaster Gift Card"
        StatusTier.LOCAL -> "$250 Ticketmaster Gift Card"
        StatusTier.LOCAL_LEGEND -> "$500 Ticketmaster Gift Card"
        else -> null
    }

    /** Clear all state (for logout) */
    fun clear() {
        settings.remove(KEY_TUTORIAL_SEEN)
        settings.remove(KEY_PLANS_CREATED)
        settings.remove(KEY_YOCAL_PLANS_CREATED)
        settings.remove(KEY_MANUAL_PLANS_CREATED)
        settings.remove(KEY_HAS_PAID_SUB)
        settings.remove(KEY_PAID_SUB_EXPIRY)
        settings.remove(KEY_REWARD_EXPIRY)
        settings.remove(KEY_LAST_REWARDED_TIER)
        settings.remove(KEY_HAS_SEEN_PAYWALL)
    }

    companion object {
        private const val KEY_TUTORIAL_SEEN = "yocal_tutorial_seen"
        private const val KEY_PLANS_CREATED = "yocal_plans_created"
        private const val KEY_YOCAL_PLANS_CREATED = "yocal_plans_created_yocal"
        private const val KEY_MANUAL_PLANS_CREATED = "yocal_plans_created_manual"
        private const val KEY_HAS_PAID_SUB = "yocal_has_paid_sub"
        private const val KEY_PAID_SUB_EXPIRY = "yocal_paid_sub_expiry"
        private const val KEY_REWARD_EXPIRY = "yocal_reward_expiry"
        private const val KEY_LAST_REWARDED_TIER = "yocal_last_rewarded_tier"
        private const val KEY_HAS_SEEN_PAYWALL = "yocal_has_seen_paywall"
    }
}
