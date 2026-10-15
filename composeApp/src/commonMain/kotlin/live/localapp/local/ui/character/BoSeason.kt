package live.localapp.local.ui.character

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import local.composeapp.generated.resources.Res
import local.composeapp.generated.resources.bo_tiny
import local.composeapp.generated.resources.bo_tiny_christmas
import local.composeapp.generated.resources.bo_tiny_pumpkin
import local.composeapp.generated.resources.bo_tiny_turkey
import local.composeapp.generated.resources.bo_tiny_valentine
import org.jetbrains.compose.resources.DrawableResource

/**
 * Seasonal skins for Bo. Date-gated on the device's local calendar so a skin
 * switches itself on and off without a release, in whatever build is installed:
 *
 *   Oct 1–31  → [Season.Halloween]     pumpkin Bo, night-sky lobby card
 *   Nov 1–30  → [Season.Thanksgiving]  turkey Bo, harvest lobby card
 *   Dec 1–25  → [Season.Christmas]     Santa-hat Bo, evergreen lobby card
 *   Feb 1–14  → [Season.Valentines]    pink heart Bo, rose lobby card
 *   else      → [Season.None]          classic coral Bo
 *
 * Every Bo renderer reads from here: [Bo] (via [BoColors]), the splash
 * [live.localapp.local.feature.onboarding.BoCharacter], the static `bo_tiny`
 * drawable (via [boTinyRes]), and the native iOS screens (`BoArt` in
 * ChatView.swift reads [current]).
 */
object BoSeason {
    enum class Season { None, Halloween, Thanksgiving, Christmas, Valentines }

    /** Evaluated once per process — a cold start after midnight picks up a new day. */
    private val dateSeason: Season by lazy {
        val today = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        when {
            today.month == Month.OCTOBER -> Season.Halloween
            today.month == Month.NOVEMBER -> Season.Thanksgiving
            today.month == Month.DECEMBER && today.day <= 25 -> Season.Christmas
            today.month == Month.FEBRUARY && today.day <= 14 -> Season.Valentines
            else -> Season.None
        }
    }

    /**
     * DEBUG builds only (set from iOSApp.swift via the `-BoSeasonOverride`
     * launch argument) — previews another season without changing the clock.
     * Must be set before the first Bo is drawn.
     */
    var debugOverride: Season? = null

    /** iOS-friendly setter for [debugOverride]: "halloween" / "thanksgiving" / "christmas" / "valentines" / "none". */
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

    // Night-sky palette for Halloween surfaces (lobby hero)
    val nightTop     = Color(0xFF3B2257)
    val nightBottom  = Color(0xFF241536)
    val nightLip     = Color(0xFF150C20)
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

    // Harvest palette for Thanksgiving surfaces (lobby hero, Ask Bo button)
    val harvest      = Color(0xFFE8892B)
    val harvestLight = Color(0xFFFFC069)
    val harvestDeep  = Color(0xFFC2621A)
    val harvestEdge  = Color(0xFF8F4513)
    val harvestTop   = Color(0xFF7A3E1D)
    val harvestBottom = Color(0xFF4E2612)
    val harvestLip   = Color(0xFF2E1508)
    val harvestGlow  = Color(0xFFFFD27A)

    // Christmas palette (Bo keeps classic coral; Santa hat + evergreen surfaces)
    val santaRed     = Color(0xFFD7263D)
    val santaRedLight = Color(0xFFFF5A6E)
    val santaRedDeep = Color(0xFFA81C2E)
    val santaEdge    = Color(0xFF7E1422)
    val evergreenTop = Color(0xFF1F5C3A)
    val evergreenBottom = Color(0xFF123A24)
    val evergreenLip = Color(0xFF0A2416)
    val gold         = Color(0xFFFFD166)

    // Valentine's palette
    val pink         = Color(0xFFFF7EB6)
    val pinkLight    = Color(0xFFFFC2DD)
    val rose         = Color(0xFFE0457B)
    val roseDeep     = Color(0xFFB8305F)
    val roseEdge     = Color(0xFF8E2149)
    val roseLip      = Color(0xFF6E1A38)
    val blush        = Color(0xFFFFE3EE)

    /** Body fill / light tint / cheek+accent tone for the active season. */
    internal val body: Color? get() = when (current) {
        Season.Halloween -> pumpkin
        Season.Thanksgiving -> turkey
        Season.Valentines -> pink
        Season.Christmas, Season.None -> null
    }
    internal val bodyLight: Color? get() = when (current) {
        Season.Halloween -> pumpkinLight
        Season.Thanksgiving -> turkeyLight
        Season.Valentines -> pinkLight
        Season.Christmas, Season.None -> null
    }
    internal val bodyDeep: Color? get() = when (current) {
        Season.Halloween -> pumpkinDeep
        Season.Thanksgiving -> turkeyCheek
        Season.Valentines -> rose
        Season.Christmas, Season.None -> null
    }

    /** The turkey's beak replaces Bo's mouth (they'd overlap); eyes carry the expression. */
    internal val hidesMouth: Boolean get() = isThanksgiving

    /** Valentine's Bo draws heart cheeks (in [drawSeasonOnFace]) instead of the oval blush. */
    internal val hidesCheeks: Boolean get() = isValentines

    /** Primary accent for themed surfaces (Ask Bo button, card rim, "Bo" label); null = classic coral. */
    val accent: Color? get() = when (current) {
        Season.Halloween -> pumpkin
        Season.Thanksgiving -> harvest
        Season.Christmas -> santaRed
        Season.Valentines -> rose
        Season.None -> null
    }
}

/** The tiny static Bo used in chat bubbles, paywall, version gate, splash. */
val boTinyRes: DrawableResource
    get() = when (BoSeason.current) {
        BoSeason.Season.Halloween -> Res.drawable.bo_tiny_pumpkin
        BoSeason.Season.Thanksgiving -> Res.drawable.bo_tiny_turkey
        BoSeason.Season.Christmas -> Res.drawable.bo_tiny_christmas
        BoSeason.Season.Valentines -> Res.drawable.bo_tiny_valentine
        BoSeason.Season.None -> Res.drawable.bo_tiny
    }

// ─── Season hooks — every Bo renderer calls these around its body/face ──────
// (100×100 body viewBox — same coordinates as the body path)

/** Before the body fill: things that sit behind Bo (pumpkin stem, turkey tail, floating hearts). */
internal fun DrawScope.drawSeasonBehindBody(ink: Color, level: Int?) {
    when (BoSeason.current) {
        BoSeason.Season.Halloween -> if (!levelWearsHat(level)) drawPumpkinStem(ink)
        BoSeason.Season.Thanksgiving -> drawTurkeyTail(ink)
        BoSeason.Season.Valentines -> drawFloatingHearts(ink)
        BoSeason.Season.Christmas, BoSeason.Season.None -> Unit
    }
}

/** After the body fill, before the outline: surface detail (pumpkin ribs). */
internal fun DrawScope.drawSeasonOnBody() {
    if (BoSeason.isHalloween) drawPumpkinRibs()
}

/** After eyes/mouth: things on the face/head (turkey beak, Santa hat, heart cheeks). */
internal fun DrawScope.drawSeasonOnFace(ink: Color, level: Int?) {
    when (BoSeason.current) {
        BoSeason.Season.Thanksgiving -> drawTurkeyBeak(ink)
        BoSeason.Season.Christmas -> if (!levelWearsHat(level)) drawSantaHat(ink)
        BoSeason.Season.Valentines -> drawHeartCheeks()
        BoSeason.Season.Halloween, BoSeason.Season.None -> Unit
    }
}

/** Level props that sit on Bo's head and would collide with a stem or Santa hat. */
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
