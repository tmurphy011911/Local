package live.localapp.local.domain.model

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Locks Plan.lastDayDate()/isLastDayPast() — the client-side past detection that
 * replaced the never-maintained DB `is_past` flag. Plans carry no machine date;
 * the year is inferred from createdAt (see Plan.kt). lastDayDate() is pure (no
 * clock), so the parsing + year-inference is asserted directly.
 */
class PlanPastDetectionTest {

    private fun plan(createdAt: String, vararg dayLabels: String, isPast: Boolean = false) = Plan(
        id = "p", title = "t", city = "Boston",
        days = dayLabels.map { PlanDay(label = it, shortName = it.take(3), slots = emptyList()) },
        attendees = emptyList(),
        createdAt = createdAt,
        dateDisplay = "",
        isPast = isPast,
    )

    @Test
    fun singleDay_usesCreatedYear() {
        assertEquals(LocalDate(2026, 6, 26), plan("2026-06-20", "Friday, June 26").lastDayDate())
    }

    @Test
    fun multiDay_takesLastDay() {
        assertEquals(
            LocalDate(2026, 6, 30),
            plan("2026-06-20", "Sat, Jun 29", "Sun, Jun 30").lastDayDate(),
        )
    }

    @Test
    fun decemberToJanuary_wrapsToNextYear() {
        // Created late December for an early-January event → next year.
        assertEquals(LocalDate(2026, 1, 2), plan("2025-12-28", "Friday, January 2").lastDayDate())
    }

    @Test
    fun unparseableLabel_isNull_andNotPast() {
        val p = plan("2026-06-20", "Day 1")
        assertNull(p.lastDayDate())
        // Safe fallback: an unparseable plan stays visible (not hidden as past).
        assertFalse(p.isLastDayPast())
    }

    @Test
    fun clearlyOldPlan_isPast() {
        assertTrue(plan("2020-01-01", "Sunday, January 5").isLastDayPast())
    }

    @Test
    fun dbIsPastFlag_forcesPast() {
        assertTrue(plan("2020-01-01", "Day 1", isPast = true).isLastDayPast())
    }
}
