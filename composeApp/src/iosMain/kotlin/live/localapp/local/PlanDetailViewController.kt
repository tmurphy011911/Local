package live.localapp.local

import androidx.compose.runtime.*
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.serialization.json.*
import live.localapp.local.domain.model.Plan
import live.localapp.local.domain.model.PlanAttendee
import live.localapp.local.domain.model.PlanDay
import live.localapp.local.domain.model.PlanSlot
import live.localapp.local.ui.plans.PlanDetailScreen

fun PlanDetailViewController(
    planJson: String,
    onBack: () -> Unit,
    onShare: () -> Unit,
) = ComposeUIViewController {
    val plan = parsePlanJson(planJson)
    // Compute isCreator via the same path Compose/Android uses: match current
    // user ID against the plan's creatorId. The JSON from Kotlin-iOS preserves
    // creatorId now, so this works for the user's own plans.
    val currentUserId = remember {
        org.koin.mp.KoinPlatform.getKoin()
            .get<live.localapp.local.domain.repository.UserPreferencesRepository>()
            .getLocalUserId()
    }
    val initialIsCreator = plan.creatorId != null && plan.creatorId == currentUserId
    var currentPlan by remember { mutableStateOf(plan) }
    PlanDetailScreen(
        plan = currentPlan,
        isCreator = initialIsCreator,
        onBack = onBack,
        onShare = onShare,
        onEditPlan = { PlanDetailBridge.onEditPlan?.invoke() },
        onEditDay = { dayLabel -> PlanDetailBridge.onEditDay?.invoke(dayLabel) },
        onSetGroupSize = { size ->
            PlanDetailBridge.onSetGroupSize?.invoke(size)
            currentPlan = currentPlan.copy(groupSize = size)
        },
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
                    isReserved = s["isReserved"]?.jsonPrimitive?.booleanOrNull ?: false,
                    imageUrl = s["imageUrl"]?.jsonPrimitive?.contentOrNull,
                    ticketUrl = s["ticketUrl"]?.jsonPrimitive?.contentOrNull,
                    venueRating = s["venueRating"]?.jsonPrimitive?.doubleOrNull,
                    venueAddress = s["venueAddress"]?.jsonPrimitive?.contentOrNull,
                    venueLat = s["venueLat"]?.jsonPrimitive?.doubleOrNull,
                    venueLng = s["venueLng"]?.jsonPrimitive?.doubleOrNull,
                    venueCategory = s["venueCategory"]?.jsonPrimitive?.contentOrNull,
                    venueWebsite = s["venueWebsite"]?.jsonPrimitive?.contentOrNull,
                    sourceUrl = s["sourceUrl"]?.jsonPrimitive?.contentOrNull,
                    reservationUrl = s["reservationUrl"]?.jsonPrimitive?.contentOrNull,
                    eventId = s["eventId"]?.jsonPrimitive?.contentOrNull,
                )
            } ?: emptyList(),
        )
    } ?: emptyList()

    val attendees = obj["attendees"]?.jsonArray?.map { aEl ->
        val a = aEl.jsonObject
        PlanAttendee(
            id = a["id"]?.jsonPrimitive?.contentOrNull ?: "",
            name = a["name"]?.jsonPrimitive?.contentOrNull ?: "",
            initials = a["initials"]?.jsonPrimitive?.contentOrNull ?: "",
            confirmed = a["confirmed"]?.jsonPrimitive?.booleanOrNull ?: false,
            avatarColor = a["avatarColor"]?.jsonPrimitive?.longOrNull ?: 0xFFFF6B6B,
        )
    } ?: emptyList()

    return Plan(
        id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
        title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
        city = obj["city"]?.jsonPrimitive?.contentOrNull ?: "",
        days = days,
        attendees = attendees,
        createdAt = "",
        dateDisplay = obj["dateDisplay"]?.jsonPrimitive?.contentOrNull ?: "",
        isPast = obj["isPast"]?.jsonPrimitive?.booleanOrNull ?: false,
        creatorId = obj["creatorId"]?.jsonPrimitive?.contentOrNull,
        creatorName = obj["creatorName"]?.jsonPrimitive?.contentOrNull,
        shareToken = obj["shareToken"]?.jsonPrimitive?.contentOrNull,
        groupSize = obj["groupSize"]?.jsonPrimitive?.intOrNull,
        confirmedCount = obj["confirmedCount"]?.jsonPrimitive?.intOrNull ?: 1,
        attendeesCanEdit = obj["attendeesCanEdit"]?.jsonPrimitive?.booleanOrNull ?: false,
    )
}
