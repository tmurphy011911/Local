package live.localapp.local.data.sync

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import live.localapp.local.data.repository.toDto
import live.localapp.local.domain.repository.PlanRepository

/**
 * Sends a [PlanMutation] to the matching Postgres RPC. Server-side these
 * functions perform the read-modify-write inside a single UPDATE — two
 * clients calling them concurrently merge naturally instead of clobbering.
 *
 * Each call is idempotent on the server, so retries from [PlanSyncer] are
 * safe.
 */
class PlanMutationRpc(
    private val supabase: SupabaseClient,
    private val planRepository: PlanRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Dispatches the right RPC for [mutation]. Throws on RPC failure. */
    suspend fun apply(mutation: PlanMutation) {
        when (mutation) {
            is PlanMutation.RemoveSlot -> rpc(
                "plan_remove_slot",
                "p_plan_id" to mutation.planId,
                "p_day_index" to mutation.dayIndex,
                "p_slot_id" to mutation.slotId,
            )
            is PlanMutation.AddSlot -> rpc(
                "plan_add_slot",
                "p_plan_id" to mutation.planId,
                "p_day_index" to mutation.dayIndex,
                "p_slot" to slotToJsonb(mutation.slot),
            )
            is PlanMutation.SwapSlot -> rpc(
                "plan_swap_slot",
                "p_plan_id" to mutation.planId,
                "p_day_index" to mutation.dayIndex,
                "p_old_slot_id" to mutation.oldSlotId,
                "p_new_slot" to slotToJsonb(mutation.newSlot),
            )
            is PlanMutation.SetReservationDetails -> patchSlot(
                mutation.planId, mutation.dayIndex, mutation.slotId,
                buildJsonObject {
                    put("reservation_state", "made")
                    put("reservation_party_size", mutation.partySize)
                    put("reservation_time", mutation.reservationTime)
                    put("ticket_state", "none")
                    // Cascade `time` so the day re-sorts — see PlanMutation
                    // commentary.
                    put("time", mutation.reservationTime)
                },
            )
            is PlanMutation.SetReservationUnavailable -> patchSlot(
                mutation.planId, mutation.dayIndex, mutation.slotId,
                buildJsonObject {
                    put("reservation_state", "unavailable")
                    put("reservation_party_size", JsonPrimitive(null as String?))
                    put("reservation_time", JsonPrimitive(null as String?))
                },
            )
            is PlanMutation.ClearReservation -> patchSlot(
                mutation.planId, mutation.dayIndex, mutation.slotId,
                buildJsonObject {
                    put("reservation_state", "none")
                    put("reservation_party_size", JsonPrimitive(null as String?))
                    put("reservation_time", JsonPrimitive(null as String?))
                },
            )
            is PlanMutation.SetTicketDetails -> patchSlot(
                mutation.planId, mutation.dayIndex, mutation.slotId,
                buildJsonObject {
                    put("ticket_state", "bought")
                    put("ticket_count", mutation.ticketCount)
                    put("reservation_state", "none")
                    put("reservation_party_size", JsonPrimitive(null as String?))
                    put("reservation_time", JsonPrimitive(null as String?))
                },
            )
            is PlanMutation.SetTicketsUnavailable -> patchSlot(
                mutation.planId, mutation.dayIndex, mutation.slotId,
                buildJsonObject {
                    put("ticket_state", "unavailable")
                    put("ticket_count", JsonPrimitive(null as String?))
                },
            )
            is PlanMutation.ClearTickets -> patchSlot(
                mutation.planId, mutation.dayIndex, mutation.slotId,
                buildJsonObject {
                    put("ticket_state", "none")
                    put("ticket_count", JsonPrimitive(null as String?))
                },
            )
            is PlanMutation.SetSlotReserved -> patchSlot(
                mutation.planId, mutation.dayIndex, mutation.slotId,
                buildJsonObject {
                    put("reservation_state", if (mutation.reserved) "made" else "none")
                },
            )
            is PlanMutation.AddDay -> rpc(
                "plan_add_day",
                "p_plan_id" to mutation.planId,
                "p_day" to dayToJsonb(mutation.day),
            )
            is PlanMutation.RemoveDay -> rpc(
                "plan_remove_day",
                "p_plan_id" to mutation.planId,
                "p_day_index" to mutation.dayIndex,
            )
            is PlanMutation.ReplaceDay -> rpc(
                "plan_replace_day",
                "p_plan_id" to mutation.planId,
                "p_day_index" to mutation.dayIndex,
                "p_day" to dayToJsonb(mutation.day),
            )
            is PlanMutation.SetPlanName -> rpc(
                "plan_set_name",
                "p_plan_id" to mutation.planId,
                "p_name" to mutation.name,
            )
            is PlanMutation.SetGroupSize -> rpc(
                "plan_set_group_size",
                "p_plan_id" to mutation.planId,
                "p_size" to mutation.size,
            )
            is PlanMutation.SetAttendeesCanEdit -> rpc(
                "plan_set_attendees_can_edit",
                "p_plan_id" to mutation.planId,
                "p_can_edit" to mutation.canEdit,
            )
            is PlanMutation.DeletePlan -> {
                // No RPC needed — the existing repo method does a single
                // DELETE which is already atomic.
                planRepository.deletePlan(mutation.planId)
            }
        }
    }

    private suspend fun patchSlot(
        planId: String,
        dayIndex: Int,
        slotId: String,
        patch: JsonObject,
    ) {
        rpc(
            "plan_set_slot_field",
            "p_plan_id" to planId,
            "p_day_index" to dayIndex,
            "p_slot_id" to slotId,
            "p_patch" to patch,
        )
    }

    private suspend fun rpc(name: String, vararg params: Pair<String, Any?>) {
        val payload = buildJsonObject {
            for ((k, v) in params) {
                when (v) {
                    null -> put(k, JsonPrimitive(null as String?))
                    is JsonObject -> put(k, v)
                    is String -> put(k, JsonPrimitive(v))
                    is Int -> put(k, JsonPrimitive(v))
                    is Long -> put(k, JsonPrimitive(v))
                    is Boolean -> put(k, JsonPrimitive(v))
                    else -> put(k, JsonPrimitive(v.toString()))
                }
            }
        }
        supabase.postgrest.rpc(name, payload)
    }

    private fun slotToJsonb(slot: live.localapp.local.domain.model.PlanSlot): JsonObject {
        val dto = slot.toDto()
        return json.encodeToJsonElement(
            kotlinx.serialization.serializer<live.localapp.local.data.repository.PlanSlotDto>(),
            dto,
        ) as JsonObject
    }

    private fun dayToJsonb(day: live.localapp.local.domain.model.PlanDay): JsonObject {
        val dto = day.toDto()
        return json.encodeToJsonElement(
            kotlinx.serialization.serializer<live.localapp.local.data.repository.PlanDayDto>(),
            dto,
        ) as JsonObject
    }
}
