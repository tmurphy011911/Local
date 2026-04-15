package live.localapp.local.domain.repository

data class UserPreferences(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val phone: String? = null,
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
    val reviewPromptCount: Int = 0,
    val lastReviewPromptAt: String? = null,
    val locationMode: String = "current", // "current" or "manual"
    val manualLocationName: String? = null,
    val manualLat: Double? = null,
    val manualLng: Double? = null,
)

interface UserPreferencesRepository {
    /** Load cached user ID + onboarding flag from local storage. Null if never onboarded. */
    fun getLocalUserId(): String?
    fun isOnboardingComplete(): Boolean

    /** Save preferences to Supabase and cache user ID locally. */
    suspend fun savePreferences(prefs: UserPreferences): String

    /** Load preferences from Supabase by user ID. */
    suspend fun loadPreferences(userId: String): UserPreferences?

    /** Try to recover a profile from Supabase if local cache was lost. Returns user ID if found. */
    suspend fun recoverProfile(): String?

    /** Check if a user_profiles row exists for the given Supabase auth user ID. */
    suspend fun profileExistsForAuthId(authUserId: String): Boolean

    /** Load preferences from Supabase by auth user ID and cache locally. Returns user ID if found. */
    suspend fun recoverProfileByAuthId(authUserId: String): String?

    /** Save preferences with a specific ID (used when creating profile for authenticated user). */
    suspend fun savePreferencesWithId(id: String, prefs: UserPreferences): String

    /** Clear local cache (logout). */
    fun clearLocal()
}
