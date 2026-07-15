package live.localapp.local.data.repository

import com.russhwolf.settings.Settings
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import live.localapp.local.domain.repository.CachedManualLocation
import live.localapp.local.domain.repository.CachedRadiusFilter
import live.localapp.local.domain.repository.ProfileStatus
import live.localapp.local.domain.repository.UserPreferences
import live.localapp.local.domain.repository.UserPreferencesRepository
import live.localapp.local.domain.repository.UsernameUpdateResult

@Serializable
private data class UserProfileDto(
    val id: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    // Auto-assigned on insert + protected on update by DB triggers. General
    // saves send the loaded value (or null) back; the guard trigger ignores a
    // null and preserves the flag, so these writes can't damage the handle.
    val username: String? = null,
    @SerialName("username_is_custom") val usernameIsCustom: Boolean = false,
    val phone: String? = null,
    val email: String? = null,
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
    // Write-once onboarding anchor. The DB guard trigger makes these immutable
    // once set, so re-sending them on every general save is a harmless no-op.
    @SerialName("hometown_name") val hometownName: String? = null,
    @SerialName("hometown_lat") val hometownLat: Double? = null,
    @SerialName("hometown_lng") val hometownLng: Double? = null,
    // Server-stamped on first capture (guard trigger owns it); we only read it.
    @SerialName("hometown_set_at") val hometownSetAt: String? = null,
    // Server-owned (guard trigger protects it on write); we only read it.
    @SerialName("trial_ends_at") val trialEndsAt: String? = null,
    // Server-owned join timestamp (DEFAULT NOW()); read-only on the client.
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
private data class UpsertResult(val id: String)

// Targeted username write. Only the handle is sent — the DB guard trigger sets
// username_is_custom, and a too-narrow payload avoids touching the rest of the
// profile.
@Serializable
private data class UsernameUpdateDto(val username: String)

class UserPreferencesRepositoryImpl(
    private val supabaseClient: SupabaseClient,
) : UserPreferencesRepository {

    private val settings: Settings = Settings()

    override fun getLocalUserId(): String? =
        settings.getStringOrNull(KEY_USER_ID)

    override fun isOnboardingComplete(): Boolean =
        settings.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    override fun getLocalFirstName(): String? =
        settings.getStringOrNull(KEY_FIRST_NAME)?.takeIf { it.isNotBlank() }

    override suspend fun savePreferences(prefs: UserPreferences): String {
        val existingId = getLocalUserId()

        val dto = UserProfileDto(
            id = existingId,
            firstName = prefs.firstName,
            lastName = prefs.lastName,
            phone = prefs.phone,
            email = prefs.email,
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
            hometownName = prefs.hometownName,
            hometownLat = prefs.hometownLat,
            hometownLng = prefs.hometownLng,
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
        prefs.firstName?.takeIf { it.isNotBlank() }?.let { settings.putString(KEY_FIRST_NAME, it) }
            ?: settings.remove(KEY_FIRST_NAME)
        if (prefs.city.isNotBlank()) settings.putString(KEY_USER_CITY, prefs.city)

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
                username = dto.username,
                usernameIsCustom = dto.usernameIsCustom,
                phone = dto.phone,
                email = dto.email,
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
                hometownName = dto.hometownName,
                hometownLat = dto.hometownLat,
                hometownLng = dto.hometownLng,
                hometownSetAt = dto.hometownSetAt,
                trialEndsAt = dto.trialEndsAt,
                createdAt = dto.createdAt,
            ).also { prefs ->
                prefs.firstName?.takeIf { it.isNotBlank() }?.let { settings.putString(KEY_FIRST_NAME, it) }
                    ?: settings.remove(KEY_FIRST_NAME)
                if (prefs.city.isNotBlank()) settings.putString(KEY_USER_CITY, prefs.city)
                // Mirror the server-owned trial end so PremiumGate can treat an
                // active trial as full Pro access (bypass the paywall).
                mirrorTrialEndsAt(dto.trialEndsAt)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Mirror the server-owned `user_profiles.trial_ends_at` into the local
     * setting PremiumGate reads. Every profile read path (load + both recover
     * paths) must call this — otherwise a path that re-caches the profile but
     * skips the trial leaves the gate seeing no trial after a reinstall, even
     * though the server still holds an active one. Empty/blank clears it.
     */
    private fun mirrorTrialEndsAt(trialEndsAt: String?) {
        trialEndsAt?.takeIf { it.isNotBlank() }
            ?.let { settings.putString(live.localapp.local.ui.chat.PremiumGate.KEY_TRIAL_ENDS_AT, it) }
            ?: settings.remove(live.localapp.local.ui.chat.PremiumGate.KEY_TRIAL_ENDS_AT)
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
            dto.firstName?.takeIf { it.isNotBlank() }?.let { settings.putString(KEY_FIRST_NAME, it) }
            if (dto.city.isNotBlank()) settings.putString(KEY_USER_CITY, dto.city)
            // Mirror the server-owned trial end so PremiumGate treats an active
            // trial as Pro access. Without this, a reinstall+relogin (local
            // Settings wiped) leaves the mirror blank and the user is wrongly
            // shown "trial expired" until loadPreferences() later repopulates it.
            mirrorTrialEndsAt(dto.trialEndsAt)
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

    override suspend fun getProfileStatus(authUserId: String): ProfileStatus {
        return try {
            val dto = supabaseClient.from("user_profiles")
                .select {
                    filter { eq("id", authUserId) }
                }
                .decodeSingleOrNull<UserProfileDto>()
            when {
                dto == null -> ProfileStatus.Missing
                dto.onboardingComplete -> ProfileStatus.Complete
                else -> ProfileStatus.Incomplete
            }
        } catch (_: Exception) {
            // Network failure / decode error — treat as Missing so the router
            // sends the user to ONBOARDING rather than HOME. Worst case is one
            // extra screen; better than landing a half-set-up user on a Home
            // they can't recover from.
            ProfileStatus.Missing
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
            dto.firstName?.takeIf { it.isNotBlank() }?.let { settings.putString(KEY_FIRST_NAME, it) }
            if (dto.city.isNotBlank()) settings.putString(KEY_USER_CITY, dto.city)
            // See recoverProfile(): without mirroring the trial here, a
            // reinstall+relogin shows "trial expired" while the server still
            // holds an active trial. Keep both recovery paths in sync.
            mirrorTrialEndsAt(dto.trialEndsAt)
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
            email = prefs.email,
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
            hometownName = prefs.hometownName,
            hometownLat = prefs.hometownLat,
            hometownLng = prefs.hometownLng,
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
        prefs.firstName?.takeIf { it.isNotBlank() }?.let { settings.putString(KEY_FIRST_NAME, it) }
            ?: settings.remove(KEY_FIRST_NAME)
        if (prefs.city.isNotBlank()) settings.putString(KEY_USER_CITY, prefs.city)

        return id
    }

    override suspend fun updateUsername(userId: String, username: String): UsernameUpdateResult {
        return try {
            supabaseClient.from("user_profiles")
                .update(UsernameUpdateDto(username = username)) {
                    filter { eq("id", userId) }
                }
            UsernameUpdateResult.Success
        } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            // Postgres 23505 / unique-index rejection = the handle is taken.
            if (msg.contains("23505") ||
                msg.contains("duplicate key") ||
                msg.contains("user_profiles_username")
            ) {
                UsernameUpdateResult.Taken
            } else {
                UsernameUpdateResult.Error
            }
        }
    }

    override fun clearLocal() {
        settings.remove(KEY_USER_ID)
        settings.remove(KEY_ONBOARDING_COMPLETE)
        settings.remove(KEY_USER_LAT)
        settings.remove(KEY_USER_LNG)
        settings.remove(KEY_FIRST_NAME)
        settings.remove(KEY_LOCATION_MODE)
        settings.remove(KEY_MANUAL_LOCATION_NAME)
        settings.remove(KEY_MANUAL_LAT)
        settings.remove(KEY_MANUAL_LNG)
        settings.remove(KEY_GPS_LOCATION_NAME)
        settings.remove(KEY_USER_CITY)
        settings.remove(KEY_RADIUS_FILTER)
    }

    override fun cacheManualLocationLocally(
        locationMode: String,
        manualLocationName: String?,
        manualLat: Double?,
        manualLng: Double?,
    ) {
        settings.putString(KEY_LOCATION_MODE, locationMode)
        if (manualLocationName.isNullOrBlank()) settings.remove(KEY_MANUAL_LOCATION_NAME)
        else settings.putString(KEY_MANUAL_LOCATION_NAME, manualLocationName)
        if (manualLat == null) settings.remove(KEY_MANUAL_LAT)
        else settings.putDouble(KEY_MANUAL_LAT, manualLat)
        if (manualLng == null) settings.remove(KEY_MANUAL_LNG)
        else settings.putDouble(KEY_MANUAL_LNG, manualLng)
    }

    override fun getCachedManualLocation(): CachedManualLocation = CachedManualLocation(
        locationMode = settings.getStringOrNull(KEY_LOCATION_MODE),
        manualLocationName = settings.getStringOrNull(KEY_MANUAL_LOCATION_NAME),
        manualLat = if (settings.hasKey(KEY_MANUAL_LAT)) settings.getDouble(KEY_MANUAL_LAT, 0.0) else null,
        manualLng = if (settings.hasKey(KEY_MANUAL_LNG)) settings.getDouble(KEY_MANUAL_LNG, 0.0) else null,
    )

    override fun cacheGpsLocationNameLocally(name: String) {
        if (name.isBlank()) settings.remove(KEY_GPS_LOCATION_NAME)
        else settings.putString(KEY_GPS_LOCATION_NAME, name)
    }

    override fun getCachedGpsLocationName(): String? =
        settings.getStringOrNull(KEY_GPS_LOCATION_NAME)

    override fun cacheUserCityLocally(city: String) {
        if (city.isBlank()) settings.remove(KEY_USER_CITY)
        else settings.putString(KEY_USER_CITY, city)
    }

    override fun getCachedUserCity(): String? =
        settings.getStringOrNull(KEY_USER_CITY)

    override fun cacheRadiusFilterLocally(miles: Double?) {
        // Store an explicit "Any distance" as a sentinel string so it reads back
        // distinct from "never set" (key absent) — otherwise a deliberate "Any"
        // would be re-defaulted to 25 mi on the next launch.
        settings.putString(KEY_RADIUS_FILTER, miles?.toString() ?: RADIUS_ANY_SENTINEL)
    }

    override fun getCachedRadiusFilter(): CachedRadiusFilter? {
        val raw = settings.getStringOrNull(KEY_RADIUS_FILTER) ?: return null
        return CachedRadiusFilter(if (raw == RADIUS_ANY_SENTINEL) null else raw.toDoubleOrNull())
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
        private const val KEY_FIRST_NAME = "first_name"
        private const val KEY_LOCATION_MODE = "location_mode"
        private const val KEY_MANUAL_LOCATION_NAME = "manual_location_name"
        private const val KEY_MANUAL_LAT = "manual_lat"
        private const val KEY_MANUAL_LNG = "manual_lng"
        private const val KEY_GPS_LOCATION_NAME = "gps_location_name"
        private const val KEY_USER_CITY = "user_city"
        private const val KEY_RADIUS_FILTER = "radius_filter"
        // Stored in place of a number when the user explicitly picks "Any
        // distance", so it reads back distinct from "never set" (key absent).
        private const val RADIUS_ANY_SENTINEL = "any"
    }
}
