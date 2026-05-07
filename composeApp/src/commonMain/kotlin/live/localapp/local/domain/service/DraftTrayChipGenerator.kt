package live.localapp.local.domain.service

import live.localapp.local.domain.model.ChipCategory
import live.localapp.local.domain.model.DraftTrayChip
import live.localapp.local.domain.model.DraftTrayEvent

/**
 * Pure function that turns the user's current draft-tray state into a
 * prioritized list of suggested next chips. Bo doesn't write chip labels —
 * the client computes them, so the response is instant and Bo can't invent
 * chips that don't map to real intents.
 *
 * Rules (priority 1 = most useful):
 *  - Empty tray: no chips (the chip strip is hidden)
 *  - 1 event: 3 light chips ("Find more like this", "What's near it?", "Build around this")
 *  - 2+ events same day with a gap ≥ 2h: gap-filler chips ("Pick a spot in between")
 *  - 2+ events different days: expansion chips ("Add another day", "Build multi-day")
 *  - 3+ events: BUILD chip promoted to priority 1
 *  - 4+ events: BUILD only, others collapse
 *  - Time-of-day → meal chips (breakfast/lunch/dinner/late-night)
 *  - Category → companion chips (sports → pre-game / post-game; concert → dinner before;
 *    museum → coffee nearby; etc.)
 *  - Diversity → "Add some non-music" if tray is mono-category
 *
 * Chip intents are pre-filled messages Bo will receive on tap — they include
 * concrete time/venue/category constraints derived from the tray contents,
 * not vague requests like "dinner please".
 */
object DraftTrayChipGenerator {

    fun chipsFor(tray: List<DraftTrayEvent>): List<DraftTrayChip> {
        if (tray.isEmpty()) return emptyList()

        val chips = mutableListOf<DraftTrayChip>()
        val sorted = tray.sortedBy { it.startAt ?: "" }

        // Always offer Build, with priority climbing as the tray fills
        val buildPriority = when {
            tray.size >= 4 -> 1
            tray.size >= 3 -> 1
            tray.size == 2 -> 3
            else -> 5  // 1 event — Build is available but de-emphasized
        }
        val buildIntent = "Build a plan from these specific event_ids: " +
            tray.joinToString(", ") { it.eventId } +
            ". Add transit and slot timing. Suggest meal breaks if there's a gap > 3 hours."
        chips += DraftTrayChip("Build my plan", buildIntent, buildPriority, ChipCategory.BUILD)

        if (tray.size == 1) {
            val e = tray[0]
            chips += DraftTrayChip(
                label = "Find more like this",
                intent = "Find more events similar to '${e.title}' " +
                    if (e.category != null) "in the ${e.category} category" else "",
                priority = 2,
                category = ChipCategory.COMPANION,
            )
            chips += DraftTrayChip(
                label = "What's near it?",
                intent = "What restaurants or bars are near " +
                    (e.venueName ?: e.title) + "?",
                priority = 3,
                category = ChipCategory.COMPANION,
            )
        }

        // Same-day pairs → gap-filler / meal / companion chips
        val byDate = sorted.groupBy { it.dateLabel }
        for ((date, dayEvents) in byDate) {
            if (dayEvents.size < 2) continue
            chips += companionChips(dayEvents, date)
            chips += mealChipsForDay(dayEvents, date)
        }

        // Multi-day → expansion chips
        if (byDate.size == 1 && tray.size <= 3) {
            chips += DraftTrayChip(
                label = "Add another day",
                intent = "Suggest events for the day after ${byDate.keys.first()} so I can extend this into a multi-day plan",
                priority = 4,
                category = ChipCategory.EXPANSION,
            )
        }
        if (byDate.size >= 2 && tray.size < 6) {
            chips += DraftTrayChip(
                label = "Add another day",
                intent = "Suggest events for the next day after my current latest plan day",
                priority = 4,
                category = ChipCategory.EXPANSION,
            )
        }

        // Diversity nudge if all events are one category (tray ≥ 3)
        if (tray.size >= 3) {
            val cats = tray.mapNotNull { it.category }.toSet()
            if (cats.size == 1 && cats.first() == "music") {
                chips += DraftTrayChip(
                    label = "Add some non-music",
                    intent = "I have a music-heavy plan. Suggest some non-music events (arts/food/community) on the same days",
                    priority = 3,
                    category = ChipCategory.DIVERSITY,
                )
            }
            if (tray.all { it.isFree }) {
                chips += DraftTrayChip(
                    label = "Anything paid worth it?",
                    intent = "I've added all free events. Are there any paid events these days that would really be worth it?",
                    priority = 5,
                    category = ChipCategory.DIVERSITY,
                )
            }
        }

        // Final shape: dedupe by label, sort by priority, cap to 4
        return chips.distinctBy { it.label }.sortedBy { it.priority }.take(4)
    }

    /** Companion chips for a single-day cluster (pre-game/post-game/before/after). */
    private fun companionChips(dayEvents: List<DraftTrayEvent>, date: String): List<DraftTrayChip> {
        val out = mutableListOf<DraftTrayChip>()
        val first = dayEvents.first()
        val last = dayEvents.last()
        val cat = first.category?.lowercase() ?: ""

        when {
            cat == "sports" -> {
                out += DraftTrayChip("Pre-game spot", "Find a bar or casual restaurant near ${first.venueName ?: "the venue"} on $date, walking distance, before ${first.timeLabel}", 3, ChipCategory.COMPANION)
                out += DraftTrayChip("After-game drinks", "Find a bar near ${last.venueName ?: "the venue"} on $date for after the game", 4, ChipCategory.COMPANION)
            }
            cat == "music" || cat == "comedy" || cat == "theatre" || cat == "theater" -> {
                out += DraftTrayChip("Dinner before", "Find a dinner spot near ${first.venueName ?: "the venue"} on $date, walking distance, finished by ${first.timeLabel}", 3, ChipCategory.MEAL)
                out += DraftTrayChip("Late-night drinks", "Find a late-night bar or cocktail spot near ${last.venueName ?: "the venue"} on $date for after the show", 4, ChipCategory.COMPANION)
            }
            cat == "arts" -> {
                out += DraftTrayChip("Coffee nearby", "Find a cafe near ${first.venueName ?: "the venue"} on $date, before or after", 3, ChipCategory.MEAL)
            }
            cat == "outdoors" -> {
                out += DraftTrayChip("Lunch nearby", "Find a casual lunch spot near ${first.venueName ?: "the venue"} on $date", 3, ChipCategory.MEAL)
            }
        }
        return out
    }

    /** Meal-time chips based on time-of-day. */
    private fun mealChipsForDay(dayEvents: List<DraftTrayEvent>, date: String): List<DraftTrayChip> {
        val out = mutableListOf<DraftTrayChip>()
        val hours = dayEvents.mapNotNull { hourOf(it.timeLabel) }
        val hasMorningSlot = hours.any { it < 11 }
        val hasMiddaySlot = hours.any { it in 11..15 }
        val hasEveningSlot = hours.any { it in 17..20 }
        val hasLateSlot = hours.any { it >= 21 }

        // No meal slot in the day's range → suggest one
        val foodCats = dayEvents.mapNotNull { it.category }.any { it in setOf("food", "restaurant") }
        if (!foodCats) {
            when {
                hasMorningSlot -> out += DraftTrayChip("Brunch nearby", "Find a brunch spot for $date morning", 4, ChipCategory.MEAL)
                hasMiddaySlot && !hasEveningSlot -> out += DraftTrayChip("Dinner later", "Find a dinner spot for $date evening", 4, ChipCategory.MEAL)
                hasEveningSlot && !hasLateSlot -> out += DraftTrayChip("Late-night spot", "Find a late-night spot for $date after 10 PM", 5, ChipCategory.MEAL)
            }
        }
        return out
    }

    private fun hourOf(timeLabel: String): Int? {
        if (timeLabel.isBlank() || timeLabel.equals("Time TBD", ignoreCase = true) || timeLabel.equals("TBD", ignoreCase = true)) return null
        // Parse "8 PM" / "8:30 PM" / "10:00 AM"
        val regex = Regex("""(\d{1,2})(?::(\d{2}))?\s*(AM|PM)""", RegexOption.IGNORE_CASE)
        val m = regex.find(timeLabel) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val ampm = m.groupValues[3].uppercase()
        if (ampm == "PM" && hour != 12) hour += 12
        if (ampm == "AM" && hour == 12) hour = 0
        return hour
    }
}
