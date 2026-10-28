package live.localapp.local.ui.character

import kotlinx.datetime.LocalDate
import live.localapp.local.ui.character.BoSeason.Season
import kotlin.test.Test
import kotlin.test.assertEquals

class BoSeasonTest {
    private fun at(y: Int, m: Int, d: Int) = BoSeason.seasonFor(LocalDate(y, m, d))

    @Test
    fun windowsAreInclusiveAtBothEnds() {
        assertEquals(Season.None, at(2026, 9, 30))
        assertEquals(Season.Halloween, at(2026, 10, 1))
        assertEquals(Season.Halloween, at(2026, 10, 31))
        assertEquals(Season.Thanksgiving, at(2026, 11, 1))
        assertEquals(Season.Thanksgiving, at(2026, 11, 30))
        assertEquals(Season.Christmas, at(2026, 12, 1))
        assertEquals(Season.Christmas, at(2026, 12, 25))
        assertEquals(Season.NewYear, at(2026, 12, 26))
        assertEquals(Season.NewYear, at(2027, 1, 2))
        assertEquals(Season.Winter, at(2027, 1, 3))
        assertEquals(Season.Winter, at(2027, 1, 31))
        assertEquals(Season.Valentines, at(2027, 2, 1))
        assertEquals(Season.Valentines, at(2027, 2, 14))
        assertEquals(Season.None, at(2027, 2, 15))
        assertEquals(Season.None, at(2027, 3, 9))
        assertEquals(Season.StPatricks, at(2027, 3, 10))
        assertEquals(Season.StPatricks, at(2027, 3, 17))
        assertEquals(Season.None, at(2027, 3, 18))
        assertEquals(Season.None, at(2027, 6, 30))
        assertEquals(Season.July4, at(2027, 7, 1))
        assertEquals(Season.July4, at(2027, 7, 4))
        assertEquals(Season.None, at(2027, 7, 5))
    }

    @Test
    fun easterSundayMatchesKnownDates() {
        assertEquals(LocalDate(2026, 4, 5), BoSeason.easterSunday(2026))
        assertEquals(LocalDate(2027, 3, 28), BoSeason.easterSunday(2027))
        assertEquals(LocalDate(2028, 4, 16), BoSeason.easterSunday(2028))
        assertEquals(LocalDate(2030, 4, 21), BoSeason.easterSunday(2030))
    }

    @Test
    fun easterWeekRunsPalmSundayThroughEaster() {
        // Easter 2027 = Mar 28 → Palm Sunday Mar 21 (after St. Patrick's window).
        assertEquals(Season.None, at(2027, 3, 20))
        assertEquals(Season.Easter, at(2027, 3, 21))
        assertEquals(Season.Easter, at(2027, 3, 28))
        assertEquals(Season.None, at(2027, 3, 29))
        // Easter 2028 = Apr 16 → Apr 9–16.
        assertEquals(Season.None, at(2028, 4, 8))
        assertEquals(Season.Easter, at(2028, 4, 9))
        assertEquals(Season.Easter, at(2028, 4, 16))
        assertEquals(Season.None, at(2028, 4, 17))
    }
}
