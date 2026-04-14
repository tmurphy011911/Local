package live.localapp.local.data.repository

import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository

@Serializable
private data class UserProfileDto(
    val id: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
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
    @SerialName("location_mode") val locationMode: String = "current",
    @SerialName("manual_location_name") val manualLocationName: String? = null,
    @SerialName("manual_lat") val manualLat: Double? = null,
    @SerialName("manual_lng") val manualLng: Double? = null,
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
            firstName = prefs.firstName,
            lastName = prefs.lastName,
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
            locationMode = prefs.locationMode,
            manualLocationName = prefs.manualLocationName,
            manualLat = prefs.manualLat,
            manualLng = prefs.manualLng,
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
                firstName = dto.firstName,
                lastName = dto.lastName,
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
                locationMode = dto.locationMode,
                manualLocationName = dto.manualLocationName,
                manualLat = dto.manualLat,
                manualLng = dto.manualLng,
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun recoverProfile(): String? {
        return try {
            // Only recover if we have a Supabase auth session
            val session = supabaseClient.auth.currentSessionOrNull()
            val authUserId = session?.user?.id
            if (authUserId == null) return null

            // Recover the profile matching the authenticated user
            val dto = supabaseClient.from("user_profiles")
                .select {
                    filter { eq("id", authUserId) }
                }
                .decodeSingleOrNull<UserProfileDto>()
                ?: return null

            if (!dto.onboardingComplete) return null

            val userId = dto.id ?: return null
            // Re-cache locally
            settings.putString(KEY_USER_ID, userId)
            settings.putBoolean(KEY_ONBOARDING_COMPLETE, true)
            dto.userLat?.let { settings.putDouble(KEY_USER_LAT, it) }
            dto.userLng?.let { settings.putDouble(KEY_USER_LNG, it) }
            userId
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun profileExistsForAuthId(authUserId: String): Boolean {
        return try {
            val dto = supabaseClient.from("user_profiles")
                .select {
                    filter { eq("id", authUserId) }
                }
                .decodeSingleOrNull<UserProfileDto>()
            dto != null && dto.onboardingComplete
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun recoverProfileByAuthId(authUserId: String): String? {
        return try {
            val dto = supabaseClient.from("user_profiles")
                .select {
                    filter { eq("id", authUserId) }
                }
                .decodeSingleOrNull<UserProfileDto>()
                ?: return null

            if (!dto.onboardingComplete) return null

            // Re-cache locally
            settings.putString(KEY_USER_ID, authUserId)
            settings.putBoolean(KEY_ONBOARDING_COMPLETE, true)
            dto.userLat?.let { settings.putDouble(KEY_USER_LAT, it) }
            dto.userLng?.let { settings.putDouble(KEY_USER_LNG, it) }
            authUserId
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun savePreferencesWithId(id: String, prefs: UserPreferences): String {
        val dto = UserProfileDto(
            id = id,
            firstName = prefs.firstName,
            lastName = prefs.lastName,
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
            locationMode = prefs.locationMode,
            manualLocationName = prefs.manualLocationName,
            manualLat = prefs.manualLat,
            manualLng = prefs.manualLng,
        )

        // Check if row exists already
        val existing = try {
            supabaseClient.from("user_profiles")
                .select { filter { eq("id", id) } }
                .decodeSingleOrNull<UserProfileDto>()
        } catch (_: Exception) { null }

        if (existing != null) {
            supabaseClient.from("user_profiles")
                .update(dto) { filter { eq("id", id) } }
        } else {
            supabaseClient.from("user_profiles")
                .insert(dto) { select() }
                .decodeSingle<UpsertResult>()
        }

        // Cache locally
        settings.putString(KEY_USER_ID, id)
        settings.putBoolean(KEY_ONBOARDING_COMPLETE, true)
        prefs.userLat?.let { settings.putDouble(KEY_USER_LAT, it) }
        prefs.userLng?.let { settings.putDouble(KEY_USER_LNG, it) }

        return id
    }

    override fun clearLocal() {
        settings.remove(KEY_USER_ID)
        settings.remove(KEY_ONBOARDING_COMPLETE)
        settings.remove(KEY_USER_LAT)
        settings.remove(KEY_USER_LNG)
    }

    /** Record an app open and return the updated streak count. Max +1 per calendar day. */
    fun recordAppOpenAndGetStreak(): Int {
        val today = kotlin.time.Clock.System.now().toString().take(10) // YYYY-MM-DD
        val lastOpen = settings.getStringOrNull(KEY_LAST_OPEN_DATE)
        val currentStreak = settings.getInt(KEY_STREAK_DAYS, 0)

        if (lastOpen == today) {
            // Already opened today — return current streak
            return currentStreak
        }

        val yesterday = try {
            val todayEpoch = kotlinx.datetime.LocalDate.parse(today).toEpochDays()
            kotlinx.datetime.LocalDate.fromEpochDays(todayEpoch - 1).toString()
        } catch (_: Exception) { "" }

        val newStreak = if (lastOpen == yesterday) {
            // Consecutive day — increment
            currentStreak + 1
        } else {
            // Streak broken or first open — start at 1
            1
        }

        settings.putString(KEY_LAST_OPEN_DATE, today)
        settings.putInt(KEY_STREAK_DAYS, newStreak)
        return newStreak
    }

    companion object {
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_USER_LAT = "user_lat"
        private const val KEY_USER_LNG = "user_lng"
        private const val KEY_LAST_OPEN_DATE = "streak_last_open"
        private const val KEY_STREAK_DAYS = "streak_days"
    }
}
