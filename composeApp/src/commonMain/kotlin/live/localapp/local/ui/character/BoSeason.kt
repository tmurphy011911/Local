package live.localapp.local.ui.character

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import local.composeapp.generated.resources.Res
import local.composeapp.generated.resources.bo_tiny
import local.composeapp.generated.resources.bo_tiny_christmas
import local.composeapp.generated.resources.bo_tiny_easter
import local.composeapp.generated.resources.bo_tiny_july4
import local.composeapp.generated.resources.bo_tiny_newyear
import local.composeapp.generated.resources.bo_tiny_pumpkin
import local.composeapp.generated.resources.bo_tiny_stpatricks
import local.composeapp.generated.resources.bo_tiny_turkey
import local.composeapp.generated.resources.bo_tiny_valentine
import local.composeapp.generated.resources.bo_tiny_winter
import org.jetbrains.compose.resources.DrawableResource

/**
 * Seasonal skins for Bo. Date-gated on the device's local calendar (windows are
 * inclusive) so a skin switches itself on and off without a release, in
 * whatever build is installed:
 *
 *   Dec 26 – Jan 2   → [Season.NewYear]       party hat + confetti, black/gold card
 *   Jan 3 – 31       → [Season.Winter]        earmuffs + scarf, icy-blue card
 *   Feb 1 – 14       → [Season.Valentines]    pink heart Bo, rose card
 *   Mar 10 – 17      → [Season.StPatricks]    green Bo + leprechaun hat, green/gold card
 *   Palm Sun – Easter → [Season.Easter]       lilac bunny Bo, pastel card
 *   Jul 1 – 4        → [Season.July4]         fireworks + star cheeks, navy card
 *   Oct 1 – 31       → [Season.Halloween]     pumpkin Bo, night-sky card
 *   Nov 1 – 30       → [Season.Thanksgiving]  turkey Bo, harvest card
 *   Dec 1 – 25       → [Season.Christmas]     Santa-hat Bo, evergreen card
 *   else             → [Season.None]          classic coral Bo
 *
 * Every Bo renderer reads from here: [Bo] (via [BoColors]), the splash
 * [live.localapp.local.feature.onboarding.BoCharacter], the static `bo_tiny`
 * drawable (via [boTinyRes]), and the native iOS screens (`BoArt` in
 * ChatView.swift reads [assetSuffix] / [accentArgb]).
 *
 * Artwork: Halloween / Thanksgiving / Christmas / Valentine's are drawn by hand
 * below; the rest are data in [BoSeasonArt], generated (with their static
 * assets) by `scripts/gen_bo_season_art.py`.
 */
object BoSeason {
    enum class Season {
        None, Halloween, Thanksgiving, Christmas, Valentines,
        NewYear, Winter, StPatricks, Easter, July4,
    }

    /** Which season [date] falls in. Pure — the single source of the calendar. */
    fun seasonFor(date: LocalDate): Season {
        val d = date.day
        return when (date.month) {
            Month.JANUARY -> if (d <= 2) Season.NewYear else Season.Winter
            Month.FEBRUARY -> if (d <= 14) Season.Valentines else Season.None
            Month.MARCH, Month.APRIL -> when {
                date.month == Month.MARCH && d in 10..17 -> Season.StPatricks
                isEasterWeek(date) -> Season.Easter
                else -> Season.None
            }
            Month.JULY -> if (d <= 4) Season.July4 else Season.None
            Month.OCTOBER -> Season.Halloween
            Month.NOVEMBER -> Season.Thanksgiving
            Month.DECEMBER -> if (d <= 25) Season.Christmas else Season.NewYear
            else -> Season.None
        }
    }

    /** Palm Sunday through Easter Sunday, inclusive. */
    private fun isEasterWeek(date: LocalDate): Boolean {
        val easter = easterSunday(date.year)
        return date >= easter.minus(7, DateTimeUnit.DAY) && date <= easter
    }

    /** Western (Gregorian) Easter — anonymous Gregorian algorithm. */
    internal fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val h = (19 * a + b - b / 4 - (b - (b + 8) / 25 + 1) / 3 + 15) % 30
        val l = (32 + 2 * (b % 4) + 2 * (c / 4) - h - c % 4) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate(year, month, day)
    }

    /** Evaluated once per process — a cold start after midnight picks up a new day. */
    private val dateSeason: Season by lazy {
        seasonFor(kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date)
    }

    /**
     * DEBUG builds only (set from iOSApp.swift via the `-BoSeasonOverride`
     * launch argument) — previews another season without changing the clock.
     * Must be set before the first Bo is drawn.
     */
    var debugOverride: Season? = null

    /** iOS-friendly setter for [debugOverride], e.g. "halloween", "stpatricks", "july4", "none". */
    fun setDebugOverride(name: String?) {
        debugOverride = Season.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    val current: Season get() = debugOverride ?: dateSeason
    val isHalloween: Boolean get() = current == Season.Halloween
    val isThanksgiving: Boolean get() = current == Season.Thanksgiving
    val isChristmas: Boolean get() = current == Season.Christmas
    val isValentines: Boolean get() = current == Season.Valentines

    // Pumpkin palette (Halloween)
    val pumpkin      = Color(0xFFFF8A1F)
    val pumpkinLight = Color(0xFFFFC08A)
    val pumpkinDeep  = Color(0xFFE0650A)
    val pumpkinEdge  = Color(0xFFB8500A)
    val stem         = Color(0xFF4E8A2E)
    val leaf         = Color(0xFF6BAF3A)
    val moon         = Color(0xFFFFF1C1)

    // Turkey palette (Thanksgiving)
    val turkey       = Color(0xFFA86B3C)
    val turkeyLight  = Color(0xFFD9A877)
    val turkeyCheek  = Color(0xFFE0704F)
    val featherRed   = Color(0xFFD9452B)
    val featherOrange = Color(0xFFF28C28)
    val featherYellow = Color(0xFFF2C230)
    val featherBrown = Color(0xFF8B5A2B)
    val beak         = Color(0xFFF5A623)
    val harvestGlow  = Color(0xFFFFD27A)

    // Christmas
    val santaRed     = Color(0xFFD7263D)
    val gold         = Color(0xFFFFD166)

    // Valentine's
    val pink         = Color(0xFFFF7EB6)
    val pinkLight    = Color(0xFFFFC2DD)
    val rose         = Color(0xFFE0457B)
    val blush        = Color(0xFFFFE3EE)

    // St. Patrick's / Easter body tones
    private val clover      = Color(0xFF5CC36E)
    private val cloverLight = Color(0xFFA8E6B4)
    private val cloverDeep  = Color(0xFF2E9E4A)
    private val lilac       = Color(0xFFB79CFF)
    private val lilacLight  = Color(0xFFDCCBFF)
    private val lilacCheek  = Color(0xFFFF8FB8)

    /** Body fill / light tint / cheek tone for the active season (null = classic coral). */
    internal val body: Color? get() = when (current) {
        Season.Halloween -> pumpkin
        Season.Thanksgiving -> turkey
        Season.Valentines -> pink
        Season.StPatricks -> clover
        Season.Easter -> lilac
        else -> null
    }
    internal val bodyLight: Color? get() = when (current) {
        Season.Halloween -> pumpkinLight
        Season.Thanksgiving -> turkeyLight
        Season.Valentines -> pinkLight
        Season.StPatricks -> cloverLight
        Season.Easter -> lilacLight
        else -> null
    }
    internal val bodyDeep: Color? get() = when (current) {
        Season.Halloween -> pumpkinDeep
        Season.Thanksgiving -> turkeyCheek
        Season.Valentines -> rose
        Season.StPatricks -> cloverDeep
        Season.Easter -> lilacCheek
        else -> null
    }

    /** The turkey's beak replaces Bo's mouth (they'd overlap); eyes carry the expression. */
    internal val hidesMouth: Boolean get() = isThanksgiving

    /** Seasons that draw their own cheeks (hearts, stars) in [drawSeasonOnFace] instead of the oval blush. */
    internal val hidesCheeks: Boolean get() = current == Season.Valentines || current == Season.July4

    /**
     * Colours for the themed surfaces around Bo — the lobby Ask Bo card and the
     * bottom-bar Ask Bo button. Null = the classic coral treatment.
     */
    class Surface(
        val cardGradient: List<Color>,
        val cardLip: Color,
        val cardRim: Color,
        val titleBo: Color,
        val accent: Color,
        val buttonFace: List<Color>,
        val buttonEdge: Color,
    )

    val surface: Surface? get() = when (current) {
        Season.Halloween -> Surface(
            listOf(Color(0xFF3B2257), Color(0xFF241536)), Color(0xFF150C20), pumpkin, pumpkin, pumpkin,
            listOf(Color(0xFFFFB060), pumpkin, pumpkinDeep), pumpkinEdge,
        )
        Season.Thanksgiving -> Surface(
            listOf(Color(0xFF7A3E1D), Color(0xFF4E2612)), Color(0xFF2E1508), Color(0xFFE8892B), featherYellow, Color(0xFFE8892B),
            listOf(Color(0xFFFFC069), Color(0xFFE8892B), Color(0xFFC2621A)), Color(0xFF8F4513),
        )
        Season.Christmas -> Surface(
            listOf(Color(0xFF1F5C3A), Color(0xFF123A24)), Color(0xFF0A2416), santaRed, gold, santaRed,
            listOf(Color(0xFFFF5A6E), santaRed, Color(0xFFA81C2E)), Color(0xFF7E1422),
        )
        Season.Valentines -> Surface(
            listOf(rose, Color(0xFFB8305F)), Color(0xFF6E1A38), pinkLight, blush, rose,
            listOf(pinkLight, pink, rose), Color(0xFFB8305F),
        )
        Season.NewYear -> Surface(
            listOf(Color(0xFF2A2633), Color(0xFF110F17)), Color(0xFF000000), gold, gold, Color(0xFFD9A93E),
            listOf(Color(0xFFFFE29A), gold, Color(0xFFD9A93E)), Color(0xFF9C7420),
        )
        Season.Winter -> Surface(
            listOf(Color(0xFF5B9BD5), Color(0xFF3A6EA5)), Color(0xFF24476E), Color(0xFFBFE0FF), Color(0xFFEAF4FF), Color(0xFF3A6EA5),
            listOf(Color(0xFF8EC5F5), Color(0xFF5B9BD5), Color(0xFF3A6EA5)), Color(0xFF24476E),
        )
        Season.StPatricks -> Surface(
            listOf(Color(0xFF2E9E4A), Color(0xFF1E6B32)), Color(0xFF12421F), gold, gold, Color(0xFF2E9E4A),
            listOf(Color(0xFF7BD389), Color(0xFF3FA34D), Color(0xFF2E7D32)), Color(0xFF1E5C27),
        )
        Season.Easter -> Surface(
            listOf(Color(0xFFB79CFF), Color(0xFF8F74E0)), Color(0xFF5E46A8), Color(0xFFFFE08A), Color(0xFFFFE08A), Color(0xFF8F74E0),
            listOf(Color(0xFFD7C6FF), lilac, Color(0xFF8F74E0)), Color(0xFF6A52BF),
        )
        Season.July4 -> Surface(
            listOf(Color(0xFF1F3A93), Color(0xFF142663)), Color(0xFF0B1640), santaRed, Color(0xFFFF5A6E), Color(0xFF1F3A93),
            listOf(Color(0xFF4F7DF0), Color(0xFF1F3A93), Color(0xFF142663)), Color(0xFF0B1640),
        )
        Season.None -> null
    }

    /** Primary accent for themed surfaces (send arrow, "Bo" sender label); null = classic coral. */
    val accent: Color? get() = surface?.accent

    /** ARGB of [accent] (coral when unthemed) — for SwiftUI, which can't take a Compose Color. */
    val accentArgb: Int get() = (accent ?: Color(0xFFFF6B6B)).toArgb()

    /**
     * Suffix on the iOS asset-catalog Bo images for the active season
     * (`BoTiny` + suffix, `BoDefaultClean` + suffix). "" = classic.
     */
    val assetSuffix: String get() = when (current) {
        Season.None -> ""
        Season.Halloween -> "Pumpkin"
        Season.Thanksgiving -> "Turkey"
        Season.Christmas -> "Christmas"
        Season.Valentines -> "Valentine"
        Season.NewYear -> "NewYear"
        Season.Winter -> "Winter"
        Season.StPatricks -> "StPatricks"
        Season.Easter -> "Easter"
        Season.July4 -> "July4"
    }
}

/** The tiny static Bo used in chat bubbles, paywall, version gate, splash. */
val boTinyRes: DrawableResource
    get() = when (BoSeason.current) {
        BoSeason.Season.None -> Res.drawable.bo_tiny
        BoSeason.Season.Halloween -> Res.drawable.bo_tiny_pumpkin
        BoSeason.Season.Thanksgiving -> Res.drawable.bo_tiny_turkey
        BoSeason.Season.Christmas -> Res.drawable.bo_tiny_christmas
        BoSeason.Season.Valentines -> Res.drawable.bo_tiny_valentine
        BoSeason.Season.NewYear -> Res.drawable.bo_tiny_newyear
        BoSeason.Season.Winter -> Res.drawable.bo_tiny_winter
        BoSeason.Season.StPatricks -> Res.drawable.bo_tiny_stpatricks
        BoSeason.Season.Easter -> Res.drawable.bo_tiny_easter
        BoSeason.Season.July4 -> Res.drawable.bo_tiny_july4
    }

// ─── Season hooks — every Bo renderer calls these around its body/face ──────
// (100×100 body viewBox — same coordinates as the body path)

/** Before the body fill: things that sit behind Bo (stem, tail, hearts, ears, confetti, fireworks…). */
internal fun DrawScope.drawSeasonBehindBody(ink: Color, level: Int?) {
    when (BoSeason.current) {
        BoSeason.Season.Halloween -> if (!levelWearsHat(level)) drawPumpkinStem(ink)
        BoSeason.Season.Thanksgiving -> drawTurkeyTail(ink)
        BoSeason.Season.Valentines -> drawFloatingHearts(ink)
        BoSeason.Season.NewYear -> drawArt(BoSeasonArt.newYearBehind)
        BoSeason.Season.Winter -> drawArt(BoSeasonArt.winterBehind)
        BoSeason.Season.StPatricks -> drawArt(BoSeasonArt.stPatricksBehind)
        BoSeason.Season.Easter -> drawArt(BoSeasonArt.easterBehind)
        BoSeason.Season.July4 -> drawArt(BoSeasonArt.july4Behind)
        BoSeason.Season.Christmas, BoSeason.Season.None -> Unit
    }
}

/** After the body fill, before the outline: surface detail (pumpkin ribs). */
internal fun DrawScope.drawSeasonOnBody() {
    if (BoSeason.isHalloween) drawPumpkinRibs()
}

/** After eyes/mouth: things on the face/head (beak, hats, heart/star cheeks, scarf…). */
internal fun DrawScope.drawSeasonOnFace(ink: Color, level: Int?) {
    val hat = levelWearsHat(level)
    when (BoSeason.current) {
        BoSeason.Season.Thanksgiving -> drawTurkeyBeak(ink)
        BoSeason.Season.Christmas -> if (!hat) drawSantaHat(ink)
        BoSeason.Season.Valentines -> drawHeartCheeks()
        BoSeason.Season.NewYear -> drawArtFront(BoSeasonArt.newYearFront, BoSeasonArt.newYearFrontNoHat, hat)
        BoSeason.Season.Winter -> drawArtFront(BoSeasonArt.winterFront, BoSeasonArt.winterFrontNoHat, hat)
        BoSeason.Season.StPatricks -> drawArtFront(BoSeasonArt.stPatricksFront, BoSeasonArt.stPatricksFrontNoHat, hat)
        BoSeason.Season.Easter -> drawArtFront(BoSeasonArt.easterFront, BoSeasonArt.easterFrontNoHat, hat)
        BoSeason.Season.July4 -> drawArtFront(BoSeasonArt.july4Front, BoSeasonArt.july4FrontNoHat, hat)
        BoSeason.Season.Halloween, BoSeason.Season.None -> Unit
    }
}

private fun DrawScope.drawArtFront(always: List<ArtShape>, noHat: List<ArtShape>, wearsHat: Boolean) {
    drawArt(always)
    if (!wearsHat) drawArt(noHat)
}

// ─── Generated-art renderer ─────────────────────────────────────────────────

/**
 * One shape of generated seasonal art ([BoSeasonArt]). [d] is SVG path data in
 * the 100×100 body viewBox; colours are ARGB longs (alpha baked in); a non-zero
 * [rotation] turns the shape about ([pivotX], [pivotY]).
 */
internal class ArtShape(
    d: String,
    private val fill: Long?,
    private val stroke: Long?,
    private val strokeWidth: Float,
    private val rotation: Float,
    private val pivotX: Float,
    private val pivotY: Float,
    private val cap: StrokeCap,
) {
    private val path: Path = PathParser().parsePathString(d).toPath()

    fun draw(scope: DrawScope) = with(scope) {
        val paint: DrawScope.() -> Unit = {
            fill?.let { drawPath(path, Color(it)) }
            stroke?.let {
                drawPath(path, Color(it), style = Stroke(width = strokeWidth, cap = cap, join = StrokeJoin.Round))
            }
        }
        if (rotation != 0f) rotate(rotation, pivot = Offset(pivotX, pivotY)) { paint() } else paint()
    }
}

internal fun DrawScope.drawArt(shapes: List<ArtShape>) = shapes.forEach { it.draw(this) }

/** Level props that sit on Bo's head and would collide with a seasonal hat/stem/earmuffs. */
internal fun levelWearsHat(level: Int?): Boolean = level == 7 || level == 8 || level == 9

// ─── Pumpkin parts ──────────────────────────────────────────────────────────

private val stemPath = Path().apply {
    moveTo(46f, 24f)
    lineTo(46.5f, 14f)
    quadraticBezierTo(50f, 11f, 53.5f, 13f)
    lineTo(54f, 23f)
    close()
}
private val leafPath = Path().apply {
    moveTo(47f, 17f)
    quadraticBezierTo(39f, 9f, 34f, 14f)
    quadraticBezierTo(39f, 20f, 47f, 18.5f)
    close()
}
private val tendrilPath = Path().apply {
    moveTo(53.5f, 15f)
    quadraticBezierTo(60f, 9f, 64f, 14f)
    quadraticBezierTo(66f, 18f, 61f, 19f)
}
private val ribLeft = Path().apply {
    moveTo(31f, 27f)
    quadraticBezierTo(21f, 52f, 29f, 76f)
}
private val ribRight = Path().apply {
    moveTo(69f, 27f)
    quadraticBezierTo(79f, 52f, 71f, 76f)
}

/** Green stem, leaf and curly tendril; drawn behind the body so its base is covered. */
private fun DrawScope.drawPumpkinStem(ink: Color) {
    drawPath(leafPath, BoSeason.leaf)
    drawPath(leafPath, ink, style = Stroke(width = 2f, join = StrokeJoin.Round))
    drawPath(tendrilPath, BoSeason.stem, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
    drawPath(stemPath, BoSeason.stem)
    drawPath(stemPath, ink, style = Stroke(width = 2.5f, join = StrokeJoin.Round))
}

private fun DrawScope.drawPumpkinRibs() {
    val rib = Stroke(width = 2.2f, cap = StrokeCap.Round)
    drawPath(ribLeft, BoSeason.pumpkinDeep.copy(alpha = 0.55f), style = rib)
    drawPath(ribRight, BoSeason.pumpkinDeep.copy(alpha = 0.55f), style = rib)
    // Soft highlight on the upper-left lobe
    drawCircle(Color.White.copy(alpha = 0.18f), radius = 4f, center = Offset(24f, 40f))
}

// ─── Turkey parts ───────────────────────────────────────────────────────────

/** Seven tail feathers fanned around (50,50), outer→center→outer. */
private val tailFeathers: List<Pair<Float, Color>> get() = listOf(
    -63f to BoSeason.featherRed,
    -42f to BoSeason.featherOrange,
    -21f to BoSeason.featherYellow,
    0f to BoSeason.featherBrown,
    21f to BoSeason.featherYellow,
    42f to BoSeason.featherOrange,
    63f to BoSeason.featherRed,
)

private fun DrawScope.drawTurkeyTail(ink: Color) {
    val featherTopLeft = Offset(41f, 2f)   // ellipse cx=50 cy=20 rx=9 ry=18
    val featherSize = androidx.compose.ui.geometry.Size(18f, 36f)
    tailFeathers.forEach { (deg, color) ->
        rotate(deg, pivot = Offset(50f, 50f)) {
            drawOval(color, featherTopLeft, featherSize)
            drawOval(ink, featherTopLeft, featherSize, style = Stroke(width = 2.5f))
            // Quill
            drawLine(ink.copy(alpha = 0.22f), Offset(50f, 6f), Offset(50f, 28f), strokeWidth = 1.4f, cap = StrokeCap.Round)
        }
    }
}

private val wattlePath = Path().apply {
    moveTo(51f, 57f)
    quadraticBezierTo(57f, 60f, 55f, 67f)
    quadraticBezierTo(52f, 70f, 50.5f, 66f)
    quadraticBezierTo(49.5f, 61f, 51f, 57f)
    close()
}
private val beakPath = Path().apply {
    moveTo(44.5f, 54f)
    lineTo(55.5f, 54f)
    lineTo(50f, 61f)
    close()
}

private fun DrawScope.drawTurkeyBeak(ink: Color) {
    drawPath(wattlePath, BoSeason.featherRed)
    drawPath(wattlePath, ink, style = Stroke(width = 1.8f, join = StrokeJoin.Round))
    drawPath(beakPath, BoSeason.beak)
    drawPath(beakPath, ink, style = Stroke(width = 2f, join = StrokeJoin.Round))
}

// ─── Christmas parts ────────────────────────────────────────────────────────

private val hatCone = Path().apply {
    moveTo(24f, 30f)
    cubicTo(26f, 12f, 42f, 4f, 58f, 5f)
    cubicTo(72f, 6f, 82f, 13f, 90f, 24f)
    cubicTo(84f, 22f, 78f, 22f, 74f, 25f)
    cubicTo(76f, 27f, 76f, 29f, 76f, 30f)
    close()
}
private val hatShine = Path().apply {
    moveTo(32f, 22f)
    cubicTo(40f, 12f, 52f, 9f, 62f, 10f)
}

/** Santa hat sitting on Bo's head: red cone flopping right, white fur band, pom-pom. */
private fun DrawScope.drawSantaHat(ink: Color) {
    drawPath(hatCone, BoSeason.santaRed)
    drawPath(hatCone, ink, style = Stroke(width = 3f, join = StrokeJoin.Round))
    drawPath(hatShine, Color.White.copy(alpha = 0.25f), style = Stroke(width = 3f, cap = StrokeCap.Round))
    val bandTopLeft = Offset(19f, 24f)
    val bandSize = androidx.compose.ui.geometry.Size(62f, 11f)
    val bandCorner = androidx.compose.ui.geometry.CornerRadius(5.5f)
    drawRoundRect(Color.White, bandTopLeft, bandSize, bandCorner)
    drawRoundRect(ink, bandTopLeft, bandSize, bandCorner, style = Stroke(width = 3f))
    drawCircle(Color.White, radius = 5.5f, center = Offset(90f, 25f))
    drawCircle(ink, radius = 5.5f, center = Offset(90f, 25f), style = Stroke(width = 2.5f))
}

// ─── Valentine's parts ──────────────────────────────────────────────────────

/** Heart centred on (cx, cy), roughly [s]×2 wide. Same curve as the iOS/Android static art. */
internal fun heartPath(cx: Float, cy: Float, s: Float): Path = Path().apply {
    moveTo(cx, cy + s * 0.45f)
    cubicTo(cx - s * 1.1f, cy - s * 0.25f, cx - s * 0.5f, cy - s * 1.05f, cx, cy - s * 0.4f)
    cubicTo(cx + s * 0.5f, cy - s * 1.05f, cx + s * 1.1f, cy - s * 0.25f, cx, cy + s * 0.45f)
    close()
}

private class FloatingHeart(val cx: Float, val cy: Float, val s: Float, val deg: Float, val deep: Boolean, val stroke: Float)
private val floatingHearts = listOf(
    FloatingHeart(86f, 18f, 9f, 14f, deep = true, stroke = 2.2f),
    FloatingHeart(15f, 24f, 6.5f, -16f, deep = false, stroke = 2f),
    FloatingHeart(73f, 6f, 4.5f, 8f, deep = false, stroke = 1.6f),
)

private fun DrawScope.drawFloatingHearts(ink: Color) {
    floatingHearts.forEach { h ->
        rotate(h.deg, pivot = Offset(h.cx, h.cy)) {
            val p = heartPath(h.cx, h.cy, h.s)
            drawPath(p, if (h.deep) BoSeason.rose else BoSeason.pinkLight)
            drawPath(p, ink, style = Stroke(width = h.stroke, join = StrokeJoin.Round))
        }
    }
}

private fun DrawScope.drawHeartCheeks() {
    drawPath(heartPath(28f, 58f, 5f), BoSeason.rose)
    drawPath(heartPath(72f, 58f, 5f), BoSeason.rose)
}
