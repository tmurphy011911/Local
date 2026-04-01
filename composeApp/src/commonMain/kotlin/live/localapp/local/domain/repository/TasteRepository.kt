package live.localapp.local.domain.repository

import live.localapp.local.domain.model.Event
import live.localapp.local.domain.model.TasteProfile
import live.localapp.local.ui.chat.ItinerarySlot

interface TasteRepository {
    suspend fun getTasteProfile(userId: String): TasteProfile?
    suspend fun saveTasteProfile(profile: TasteProfile)
    suspend fun recordPlanApproval(userId: String, slots: List<ItinerarySlot>, events: List<Event>)
    suspend fun recordEventTap(userId: String, event: Event)
}
