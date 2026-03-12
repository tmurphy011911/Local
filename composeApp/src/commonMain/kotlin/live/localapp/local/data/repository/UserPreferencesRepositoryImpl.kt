package live.localapp.local.data.repository

import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository

@Serializable
private data class UserProfileDto(
    val id: String? = null,
    val phone: String? = null,
    val city: String = "boston",
    @SerialName("user_lat") val userLat: Double? = null,
    @SerialName("user_lng") val userLng: Double? = null,
    @SerialName("selected_neighborhoods") val selectedNeighborhoods: List<String> = emptyList(),
    @SerialName("selected_venues") val selectedVenues: List<String> = emptyList(),
    @SerialName("selected_categories") val selectedCategories: List<String> = emptyList(),
    @SerialName("notif_tickets") val notifTickets: Boolean = true,
    @SerialName("notif_nearby") val notifNearby: Boolean = true,
    @SerialName("notif_venues") val notifVenues: Boolean = true,
    @SerialName("onboarding_complete") val onboardingComplete: Boolean = false,
)

@Serializable
private data class UpsertResult(val id: String)

class UserPreferencesRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : UserPreferencesRepository {

    private val settings: Settings = Settings()

    override fun getLocalUserId(): String? =
        settings.getStringOrNull(KEY_USER_ID)

    override fun isOnboardingComplete(): Boolean =
        settings.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    override suspend fun savePreferences(prefs: UserPreferences): String {
        val existingId = getLocalUserId()

        val dto = UserProfileDto(
            id = existingId,
            phone = prefs.phone,
            city = prefs.city,
            userLat = prefs.userLat,
            userLng = prefs.userLng,
            selectedNeighborhoods = prefs.selectedNeighborhoods,
            selectedVenues = prefs.selectedVenues,
            selectedCategories = prefs.selectedCategories,
            notifTickets = prefs.notifTickets,
            notifNearby = prefs.notifNearby,
            notifVenues = prefs.notifVenues,
            onboardingComplete = true,
        )

        val result = if (existingId != null) {
            // Update existing profile
            supabaseClient.from("user_profiles")
                .update(dto) {
                    filter { eq("id", existingId) }
                }
            existingId
        } else {
            // Insert new profile, get back the generated ID
            val inserted = supabaseClient.from("user_profiles")
                .insert(dto) {
                    select()
                }
                .decodeSingle<UpsertResult>()
            inserted.id
        }

        // Cache locally
        settings.putString(KEY_USER_ID, result)
        settings.putBoolean(KEY_ONBOARDING_COMPLETE, true)
        prefs.userLat?.let { settings.putDouble(KEY_USER_LAT, it) }
        prefs.userLng?.let { settings.putDouble(KEY_USER_LNG, it) }

        return result
    }

    override suspend fun loadPreferences(userId: String): UserPreferences? {
        return try {
            val dto = supabaseClient.from("user_profiles")
                .select {
                    filter { eq("id", userId) }
                }
                .decodeSingleOrNull<UserProfileDto>()
                ?: return null

            UserPreferences(
                id = dto.id ?: userId,
                phone = dto.phone,
                city = dto.city,
                userLat = dto.userLat,
                userLng = dto.userLng,
                selectedNeighborhoods = dto.selectedNeighborhoods,
                selectedVenues = dto.selectedVenues,
                selectedCategories = dto.selectedCategories,
                notifTickets = dto.notifTickets,
                notifNearby = dto.notifNearby,
                notifVenues = dto.notifVenues,
                onboardingComplete = dto.onboardingComplete,
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun clearLocal() {
        settings.remove(KEY_USER_ID)
        settings.remove(KEY_ONBOARDING_COMPLETE)
        settings.remove(KEY_USER_LAT)
        settings.remove(KEY_USER_LNG)
    }

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_USER_LAT = "user_lat"
        private const val KEY_USER_LNG = "user_lng"
    }
}
