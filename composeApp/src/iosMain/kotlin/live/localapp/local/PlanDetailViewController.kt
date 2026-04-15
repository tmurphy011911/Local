package live.localapp.local

import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.serialization.json.*
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.domain.model.PlanTravel
import live.localapp.local.domain.model.PlanAttendee
import live.localapp.local.ui.plans.PlanDetailScreen

fun PlanDetailViewController(
    planJson: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
) = ComposeUIViewController {
    val plan = parsePlanJson(planJson)
    PlanDetailScreen(
        plan = plan,
        onBack = onBack,
        onShare = onShare,
        onEditPlan = { PlanDetailBridge.onEditPlan?.invoke() },
        onEditDay = { dayLabel -> PlanDetailBridge.onEditDay?.invoke(dayLabel) },
    )
}

private fun parsePlanJson(json: String): Plan {
    val obj = Json.parseToJsonElement(json).jsonObject
    val days = obj["days"]?.jsonArray?.map { dayEl ->
        val dayObj = dayEl.jsonObject
        PlanDay(
            label = dayObj["label"]?.jsonPrimitive?.contentOrNull ?: "",
            shortName = dayObj["shortName"]?.jsonPrimitive?.contentOrNull ?: "",
            slots = dayObj["slots"]?.jsonArray?.map { slotEl ->
                val s = slotEl.jsonObject
                PlanSlot(
                    id = s["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    time = s["time"]?.jsonPrimitive?.contentOrNull ?: "",
                    emoji = s["emoji"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = s["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    detail = s["detail"]?.jsonPrimitive?.contentOrNull ?: "",
                    price = s["price"]?.jsonPrimitive?.contentOrNull ?: "",
                    isFree = s["isFree"]?.jsonPrimitive?.booleanOrNull ?: false,
                    imageUrl = s["imageUrl"]?.jsonPrimitive?.contentOrNull,
                    ticketUrl = s["ticketUrl"]?.jsonPrimitive?.contentOrNull,
                    venueRating = s["venueRating"]?.jsonPrimitive?.doubleOrNull,
                    venueAddress = s["venueAddress"]?.jsonPrimitive?.contentOrNull,
                    venueLat = s["venueLat"]?.jsonPrimitive?.doubleOrNull,
                    venueLng = s["venueLng"]?.jsonPrimitive?.doubleOrNull,
                    reservationUrl = s["reservationUrl"]?.jsonPrimitive?.contentOrNull,
                )
            } ?: emptyList(),
        )
    } ?: emptyList()

    return Plan(
        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
        title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
        city = obj["city"]?.jsonPrimitive?.contentOrNull ?: "",
        days = days,
        attendees = emptyList(),
        createdAt = "",
        dateDisplay = obj["dateDisplay"]?.jsonPrimitive?.contentOrNull ?: "",
    )
}
