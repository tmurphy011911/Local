package live.localapp.local.domain.repository

data class UserPreferences(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    /** Auto-assigned handle (e.g. "local4821"), editable on the account page. */
    val username: String? = null,
    /** True once the user has picked their own handle. Gates the home greeting. */
    val usernameIsCustom: Boolean = false,
    val phone: String? = null,
    val email: String? = null,
    val city: String = "boston",
    val userLat: Double? = null,
    val userLng: Double? = null,
    val selectedNeighborhoods: List<String> = emptyList(),
    val selectedVenues: List<String> = emptyList(),
    val selectedCategories: List<String> = emptyList(),
    val notifTickets: Boolean = true,
    val notifNearby: Boolean = true,
    val notifVenues: Boolean = true,
    val onboardingComplete: Boolean = false,
    /** True once the user completes the lobby-launched preferences flow. The
     *  server awards 1000 FOMO on the false->true flip; the CTA hides at true. */
    val preferencesCompleted: Boolean = false,
    val reviewPromptCount: Int = 0,
    val lastReviewPromptAt: String? = null,
    val locationMode: String = "current", // "current" or "manual"
    val manualLocationName: String? = null,
    val manualLat: Double? = null,
    val manualLng: Double? = null,
    /** Durable, write-once anchor: the location chosen during onboarding. Unlike
     *  the manual/userLat fields (which track the active location), the DB makes
     *  these immutable once set, so they're safe to back "hometown" features. */
    val hometownName: String? = null,
    val hometownLat: Double? = null,
    val hometownLng: Double? = null,
    /** When the hometown was first captured (server-stamped, read-only). */
    val hometownSetAt: String? = null,
    /** 2-letter state from the geocoder for a typed home address (e.g. "PA").
     *  Authoritative at borders; the server prefers it over coord-derived state
     *  and preserves it once set (Philadelphia -> PA, not the NJ bbox neighbor). */
    val geocodedState: String? = null,
    /** Local Pro trial end (ISO-8601, server-owned). Null if unknown. */
    val trialEndsAt: String? = null,
    /** Account creation timestamp (ISO-8601, server-owned). Null if unknown. */
    val createdAt: String? = null,
)

data class CachedManualLocation(
    val locationMode: String? = null,
    val manualLocationName: String? = null,
    val manualLat: Double? = null,
    val manualLng: Double? = null,
)

/**
 * Tri-state result of inspecting `user_profiles` for a given auth id.
 *
 * Distinguishing these three states is what the cold-start router uses to
 * decide between HOME, ONBOARDING(resume), and ONBOARDING(fresh). The legacy
 * `recoverProfileByAuthId` collapsed Missing and Incomplete into a single
 * `null`, which forced the router to guess — see the auth-routing refactor
 * notes for context.
 */
enum class ProfileStatus {
    /** No row exists in `user_profiles` for this auth id. */
    Missing,
    /** Row exists but `onboarding_complete = false`. */
    Incomplete,
    /** Row exists and `onboarding_complete = true`. */
    Complete,
}

/** Outcome of a username edit. [Taken] = the DB unique index rejected it. */
enum class UsernameUpdateResult { Success, Taken, Error }

interface UserPreferencesRepository {
    /** Load cached user ID + onboarding flag from local storage. Null if never onboarded. */
    fun getLocalUserId(): String?
    fun isOnboardingComplete(): Boolean

    /** Cached first name from local storage. Null if user hasn't entered one. */
    fun getLocalFirstName(): String?

    /** Save preferences to Supabase and cache user ID locally. */
    suspend fun savePreferences(prefs: UserPreferences): String

    /** Load preferences from Supabase by user ID. */
    suspend fun loadPreferences(userId: String): UserPreferences?

    /** Try to recover a profile from Supabase if local cache was lost. Returns user ID if found. */
    suspend fun recoverProfile(): String?

    /** Check if a user_profiles row exists for the given Supabase auth user ID. */
    suspend fun profileExistsForAuthId(authUserId: String): Boolean

    /**
     * Pure query: inspect `user_profiles` for the given auth id and report
     * whether the row is Missing, Incomplete, or Complete. Used by the
     * cold-start router to pick HOME vs ONBOARDING(resume) vs ONBOARDING(fresh).
     * No side effects (no cache writes).
     */
    suspend fun getProfileStatus(authUserId: String): ProfileStatus

    /** Load preferences from Supabase by auth user ID and cache locally. Returns user ID if found. */
    suspend fun recoverProfileByAuthId(authUserId: String): String?

    /** Save preferences with a specific ID (used when creating profile for authenticated user). */
    suspend fun savePreferencesWithId(id: String, prefs: UserPreferences): String

    /**
     * Set the user's chosen handle. Targeted update (only the username column) so
     * it can't clobber the rest of the profile, and so the DB unique-index
     * violation surfaces as [UsernameUpdateResult.Taken]. The DB trigger flips
     * `username_is_custom` to true; the client doesn't manage that flag.
     */
    suspend fun updateUsername(userId: String, username: String): UsernameUpdateResult

    /**
     * Persist the lobby preferences-flow selections. Targeted update (only the
     * three selection columns + preferences_completed) so it can't clobber the
     * rest of the profile. Flipping preferences_completed to true trips the
     * server FOMO award. Returns true on success.
     */
    suspend fun savePreferencesSelection(
        userId: String,
        neighborhoods: List<String>,
        venues: List<String>,
        categories: List<String>,
    ): Boolean

    /** Clear local cache (logout). */
    fun clearLocal()

    /** Synchronously cache the manual-location selection so it survives an
     *  immediate app kill that races the Supabase upsert. Pair with
     *  [getCachedManualLocation] on cold start. */
    fun cacheManualLocationLocally(
        locationMode: String,
        manualLocationName: String?,
        manualLat: Double?,
        manualLng: Double?,
    )

    /** Read the locally-cached manual-location snapshot. Use to backfill
     *  manual-location fields the remote row is missing (e.g. user killed the
     *  app before the network save landed). */
    fun getCachedManualLocation(): CachedManualLocation

    /** Synchronously cache the last reverse-geocoded GPS city name (e.g.
     *  "Waltham, MA"). Used so the location chip renders something on cold
     *  start instead of staying blank until the geocode round-trip returns. */
    fun cacheGpsLocationNameLocally(name: String)

    /** Read the last reverse-geocoded GPS city name, or null if never set. */
    fun getCachedGpsLocationName(): String?

    /** Cache the user's onboarding city ("boston", "philadelphia", etc.) so
     *  it can be used as the chip's final fallback when there is no manual
     *  selection and no cached GPS city yet. */
    fun cacheUserCityLocally(city: String)

    /** Read the locally-cached onboarding city, or null if never set. */
    fun getCachedUserCity(): String?
}
