package live.localapp.local.ui.chat

import com.russhwolf.settings.Settings

/**
 * Manages premium gating state for Yocal chat / plan creation.
 *
 * Flow:
 * - Chat is always free
 * - First shareable plan is free
 * - After first plan, paywall appears when user tries to save/create another plan
 * - Premium users bypass all gates
 */
class PremiumGate {

    private val settings = Settings()

    /** Whether the user has seen the tutorial */
    var tutorialSeen: Boolean
        get() = settings.getBoolean(KEY_TUTORIAL_SEEN, false)
        set(value) = settings.putBoolean(KEY_TUTORIAL_SEEN, value)

    /** Number of shareable plans created */
    var plansCreated: Int
        get() = settings.getInt(KEY_PLANS_CREATED, 0)
        private set(value) = settings.putInt(KEY_PLANS_CREATED, value)

    /** Whether the user has an active premium subscription */
    var isPremium: Boolean
        get() = settings.getBoolean(KEY_IS_PREMIUM, false)
        set(value) = settings.putBoolean(KEY_IS_PREMIUM, value)

    /** Record that a shareable plan was created */
    fun recordPlanCreated() {
        plansCreated = plansCreated + 1
    }

    /** Whether the user can create a shareable plan without hitting the paywall */
    fun canCreatePlan(): Boolean {
        println("PremiumGate: canCreatePlan? isPremium=$isPremium plansCreated=$plansCreated")
        if (isPremium) return true
        return plansCreated < 1 // first plan is free
    }

    /** Whether the tutorial should show (first Yocal open) */
    fun shouldShowTutorial(): Boolean = !tutorialSeen

    /** Clear all state (for logout) */
    fun clear() {
        settings.remove(KEY_TUTORIAL_SEEN)
        settings.remove(KEY_PLANS_CREATED)
        settings.remove(KEY_IS_PREMIUM)
    }

    companion object {
        private const val KEY_TUTORIAL_SEEN = "yocal_tutorial_seen"
        private const val KEY_PLANS_CREATED = "yocal_plans_created"
        private const val KEY_IS_PREMIUM = "yocal_is_premium"
    }
}
