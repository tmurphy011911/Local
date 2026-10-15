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
import local.composeapp.generated.resources.bo_tiny_pumpkin
import local.composeapp.generated.resources.bo_tiny_turkey
import org.jetbrains.compose.resources.DrawableResource

/**
 * Seasonal skins for Bo. Date-gated on the device's local calendar so a skin
 * switches itself on and off without a release, in whatever build is installed:
 *
 *   October  → [Season.Halloween]     pumpkin Bo, night-sky lobby card
 *   November → [Season.Thanksgiving]  turkey Bo, harvest lobby card
 *   else     → [Season.None]          classic coral Bo
 *
 * Every Bo renderer reads from here: [Bo] (via [BoColors]), the splash
 * [live.localapp.local.feature.onboarding.BoCharacter], the static `bo_tiny`
 * drawable (via [boTinyRes]), and the native iOS screens (`BoArt` in
 * ChatView.swift reads [current]).
 */
object BoSeason {
    enum class Season { None, Halloween, Thanksgiving }

    /** Evaluated once per process — a cold start after midnight picks up a new month. */
    private val dateSeason: Season by lazy {
        when (kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).month) {
            Month.OCTOBER -> Season.Halloween
            Month.NOVEMBER -> Season.Thanksgiving
            else -> Season.None
        }
    }

    /**
     * DEBUG builds only (set from iOSApp.swift via the `-BoSeasonOverride`
     * launch argument) — previews another season without changing the clock.
     * Must be set before the first Bo is drawn.
     */
    var debugOverride: Season? = null

    /** iOS-friendly setter for [debugOverride]: "halloween" / "thanksgiving" / "none". */
    fun setDebugOverride(name: String?) {
        debugOverride = Season.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    val current: Season get() = debugOverride ?: dateSeason
    val isHalloween: Boolean get() = current == Season.Halloween
    val isThanksgiving: Boolean get() = current == Season.Thanksgiving

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

    /** Body fill / light tint / cheek+accent tone for the active season. */
    internal val body: Color? get() = when (current) {
        Season.Halloween -> pumpkin
        Season.Thanksgiving -> turkey
        Season.None -> null
    }
    internal val bodyLight: Color? get() = when (current) {
        Season.Halloween -> pumpkinLight
        Season.Thanksgiving -> turkeyLight
        Season.None -> null
    }
    internal val bodyDeep: Color? get() = when (current) {
        Season.Halloween -> pumpkinDeep
        Season.Thanksgiving -> turkeyCheek
        Season.None -> null
    }

    /** The turkey's beak replaces Bo's mouth (they'd overlap); eyes carry the expression. */
    internal val hidesMouth: Boolean get() = isThanksgiving

    /** Primary accent for themed surfaces (Ask Bo button, card rim, "Bo" label); null = classic coral. */
    val accent: Color? get() = when (current) {
        Season.Halloween -> pumpkin
        Season.Thanksgiving -> harvest
        Season.None -> null
    }
}

/** The tiny static Bo used in chat bubbles, paywall, version gate, splash. */
val boTinyRes: DrawableResource
    get() = when (BoSeason.current) {
        BoSeason.Season.Halloween -> Res.drawable.bo_tiny_pumpkin
        BoSeason.Season.Thanksgiving -> Res.drawable.bo_tiny_turkey
        BoSeason.Season.None -> Res.drawable.bo_tiny
    }

// ─── Season hooks — every Bo renderer calls these around its body/face ──────
// (100×100 body viewBox — same coordinates as the body path)

/** Before the body fill: things that sit behind Bo (pumpkin stem, turkey tail). */
internal fun DrawScope.drawSeasonBehindBody(ink: Color, level: Int?) {
    when (BoSeason.current) {
        BoSeason.Season.Halloween -> if (!levelWearsHat(level)) drawPumpkinStem(ink)
        BoSeason.Season.Thanksgiving -> drawTurkeyTail(ink)
        BoSeason.Season.None -> Unit
    }
}

/** After the body fill, before the outline: surface detail (pumpkin ribs). */
internal fun DrawScope.drawSeasonOnBody() {
    if (BoSeason.isHalloween) drawPumpkinRibs()
}

/** After eyes/mouth: things on the face (turkey beak + wattle). */
internal fun DrawScope.drawSeasonOnFace(ink: Color) {
    if (BoSeason.isThanksgiving) drawTurkeyBeak(ink)
}

/** Level props that sit on Bo's head and would collide with the pumpkin stem. */
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
