package live.localapp.local.feature.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import live.localapp.local.domain.repository.ProfileStatus
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.observability.Analytics

/**
 * What the cold-start router decides we should land on after the splash.
 *
 * Mapped 1:1 onto `AppState` in App.kt — kept as a separate type so the
 * resolver stays independent of the App composable for testability.
 *
 * `authUserId` rides along on `Home` / `Onboarding` so callers don't have
 * to redo the session read.
 */
sealed class RouteDestination {
    /** Existing user with a complete profile — drop into the lobby. */
    data class Home(val authUserId: String) : RouteDestination()

    /**
     * Authenticated user whose profile is missing or incomplete. The Onboarding
     * screen should resume from whatever has been collected so far (firstName,
     * phone, location) if `resume=true`, otherwise start from the first step.
     */
    data class Onboarding(val authUserId: String, val resume: Boolean) : RouteDestination()

    /** No (or unrecoverable) session — user must sign in. */
    object Auth : RouteDestination()
}

/**
 * Resolves what to show after the splash, based on Supabase session state
 * and `user_profiles` row state. Pure async function — no Compose, no global
 * mutable state. Lives in `feature/auth` because session is the central input.
 *
 * State machine:
 *
 * ```
 *   sessionStatus (awaited, 3s timeout)
 *     │
 *     ├── Authenticated(session)    → Home (or DeepLink if pending)
 *     │                                — incomplete profile is Profile-tab's
 *     │                                  concern, not the splash router's
 *     ├── NotAuthenticated  ─────────┐
 *     ├── RefreshFailure    ─────────┤ → clearLocal() then Auth (or DeepLink)
 *     └── <timeout / exception>  ────┘
 * ```
 *
 * `RouteDestination.Onboarding` is kept on the type for future use (e.g. if
 * we ever want to re-add a "resume onboarding" detour for half-set-up users),
 * but the resolver never returns it today.
 *
 * The two security invariants the router enforces:
 *
 * 1. **No session, no Home.** Stale local prefs alone never produce a Home
 *    destination. This was the original "dropped on the lobby when logged
 *    out" bug.
 *
 * 2. **RefreshFailure clears the local cache** before routing to Auth. A
 *    different user might sign in next, and they must not inherit the
 *    previous account's cached profile blob.
 */
class ColdStartRouter(
    private val supabaseClient: SupabaseClient,
    private val userPrefsRepo: UserPreferencesRepository,
) {
    /**
     * Resolves the *auth-based* landing destination only. Deep links are no
     * longer a concern here — [live.localapp.local.feature.deeplink.DeepLinkController]
     * owns the pending shared link and the UI layers overlay it on top of
     * whatever this returns (logged-out → preview before Auth, logged-in →
     * overlay on Home). Keeping the router purely auth-driven removed the
     * snapshot race where a link arriving after [resolve] ran was missed.
     */
    suspend fun resolve(): RouteDestination {
        // Wait for Supabase auth to hydrate from disk. `currentSessionOrNull()`
        // returns null while sessionStatus == Initializing, which would
        // otherwise bounce logged-in users to AUTH on cold start. 3s is enough
        // for any reasonable disk read; longer than that and we'd rather show
        // the AUTH screen (user can retry) than hang the splash forever.
        val status = withTimeoutOrNull(SESSION_INIT_TIMEOUT_MS) {
            supabaseClient.auth.sessionStatus.first { it !is SessionStatus.Initializing }
        }

        return when (status) {
            is SessionStatus.Authenticated -> resolveAuthenticated(
                userId = status.session.user?.id.orEmpty(),
            )
            is SessionStatus.NotAuthenticated,
            is SessionStatus.RefreshFailure -> resolveUnauthenticated(
                clearCache = status is SessionStatus.RefreshFailure,
            )
            // Timeout (status == null) or anything we don't recognize — treat
            // as unauthenticated. The user can retry by signing in.
            else -> resolveUnauthenticated(
                clearCache = false,
            )
        }
    }

    private suspend fun resolveAuthenticated(
        userId: String,
    ): RouteDestination {
        if (userId.isEmpty()) {
            // Authenticated status but no user id — defensive fallback.
            return resolveUnauthenticated(clearCache = false)
        }

        // Attach analytics to the user. Sign-in's identify() only runs on the
        // interactive OTP path; a recovered session skips it, so without this
        // every returning-user event (and the anonymous app_opened that already
        // fired this launch) stays unattributed. identify() is idempotent.
        Analytics.identify(userId = userId)

        // Best-effort: hydrate the local cache from server so Home/Profile have
        // something to show even when the user's network is flaky on next
        // launch. Fire-and-forget — we don't gate the route on the result.
        runCatching { userPrefsRepo.recoverProfileByAuthId(userId) }

        // Product rule: any valid session goes to Home. Incomplete/Missing
        // profile data is Profile-tab's concern (the "Enter Name" placeholder
        // and any other prompts to finish setup). The splash router does not
        // detour a logged-in user through Onboarding — that flow is only
        // reachable post-OTP for genuinely new accounts.
        return RouteDestination.Home(authUserId = userId)
    }

    private fun resolveUnauthenticated(
        clearCache: Boolean,
    ): RouteDestination {
        // Test-auth bypass: this mode never creates a real Supabase session
        // (verifyOtp mints a local "test-<phone>" user), so a "logged in" test
        // user exists only in local prefs. Without this, killing and relaunching
        // the app always bounces them to sign-in. Honor the local session here.
        // Guarded on the bypass flag AND !clearCache so the strict prod
        // invariant (no real session → never Home) is completely untouched when
        // TEST_AUTH_BYPASS is false.
        if (live.localapp.local.Config.TEST_AUTH_BYPASS && !clearCache) {
            val localId = userPrefsRepo.getLocalUserId()
            if (localId != null && userPrefsRepo.isOnboardingComplete()) {
                return RouteDestination.Home(authUserId = localId)
            }
        }
        if (clearCache) {
            // RefreshFailure: token chain is broken, sign-in is required.
            // Drop the local profile cache so the next sign-in (possibly a
            // different account) starts clean.
            runCatching { userPrefsRepo.clearLocal() }
        }
        return RouteDestination.Auth
    }

    companion object {
        /** Hard ceiling on how long we wait for Supabase auth init. */
        private const val SESSION_INIT_TIMEOUT_MS = 3000L
    }
}
