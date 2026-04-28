package live.localapp.local.domain.repository

/**
 * Returns short, natural-feeling search prompts tailored to the user's
 * current location. Used by the Home typewriter placeholder.
 */
interface YocalPromptsRepository {
    /**
     * Fetch (or return cached) list of prompts for the given location.
     * Returns an empty list on failure so callers can fall back to a
     * hardcoded set.
     */
    suspend fun getPrompts(lat: Double, lng: Double): List<String>
}
