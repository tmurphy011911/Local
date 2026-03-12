package live.localapp.local.domain.repository

data class UserPreferences(
    val id: String,
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
)

interface UserPreferencesRepository {
    /** Load cached user ID + onboarding flag from local storage. Null if never onboarded. */
    fun getLocalUserId(): String?
    fun isOnboardingComplete(): Boolean

    /** Save preferences to Supabase and cache user ID locally. */
    suspend fun savePreferences(prefs: UserPreferences): String

    /** Load preferences from Supabase by user ID. */
    suspend fun loadPreferences(userId: String): UserPreferences?

    /** Clear local cache (logout). */
    fun clearLocal()
}
