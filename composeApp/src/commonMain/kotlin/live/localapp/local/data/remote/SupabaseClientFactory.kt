package live.localapp.local.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json
import live.localapp.local.Config

object SupabaseClientFactory {

    @OptIn(SupabaseInternal::class)
    fun create(): SupabaseClient {
        return createSupabaseClient(
            supabaseUrl = Config.SUPABASE_URL,
            supabaseKey = Config.SUPABASE_ANON_KEY
        ) {
            defaultSerializer = KotlinXSerializer(Json {
                ignoreUnknownKeys = true
                isLenient = true
                // A null in a non-nullable DTO field WITH a default coerces to
                // the default instead of throwing. EventDto.venueCity's `= ""`
                // was written assuming this flag was on for the postgrest path —
                // it wasn't, and a single null venue_city row a user had
                // favorited hard-crashed every app open (2026-08-12 incident).
                coerceInputValues = true
            })
            // Tight timeouts on the DB client. Without these the client used
            // the OS default, so a stalled RPC (flaky wifi, dropped keep-alive)
            // hung for ~60s+ before surfacing "network connection was lost".
            // A DB query that hasn't responded in 20s is dead — failing fast
            // lets ChatViewModel's broadening retry fire immediately instead of
            // making the user wait out the stall. The realtime socket manages
            // its own connection and is unaffected by these request timeouts.
            httpConfig {
                install(HttpTimeout) {
                    requestTimeoutMillis = 20_000
                    connectTimeoutMillis = 10_000
                    socketTimeoutMillis = 20_000
                }
            }
            install(Postgrest)
            install(Auth)
            install(Realtime)
        }
    }
}
