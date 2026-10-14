package live.localapp.local.ui.character

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import local.composeapp.generated.resources.Res
import local.composeapp.generated.resources.bo_tiny
import local.composeapp.generated.resources.bo_tiny_pumpkin
import org.jetbrains.compose.resources.DrawableResource

/**
 * Seasonal skins for Bo. Date-gated on the device's local calendar so a skin
 * switches itself on and off without a release — pumpkin Bo runs all of
 * October and reverts to coral on Nov 1 in whatever build is installed.
 *
 * Every Bo renderer reads from here: [Bo] (via [BoColors]), the splash
 * [live.localapp.local.feature.onboarding.BoCharacter], and the static
 * `bo_tiny` drawable (via [boTinyRes]).
 */
object BoSeason {
    /** Evaluated once per process — a cold start after midnight Oct 31 picks up the change. */
    val isHalloween: Boolean by lazy {
        val today = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        today.month == Month.OCTOBER
    }

    // Pumpkin palette
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
}

/** The tiny static Bo used in chat bubbles, paywall, version gate, splash. */
val boTinyRes: DrawableResource
    get() = if (BoSeason.isHalloween) Res.drawable.bo_tiny_pumpkin else Res.drawable.bo_tiny

// ─── Pumpkin parts (100×100 body viewBox — same coordinates as the body path) ─

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

/**
 * Green stem, leaf and curly tendril. Draw BEFORE the body so the body covers
 * the stem's base and it reads as growing out of the top.
 */
internal fun DrawScope.drawPumpkinStem(ink: Color) {
    drawPath(leafPath, BoSeason.leaf)
    drawPath(leafPath, ink, style = Stroke(width = 2f, join = StrokeJoin.Round))
    drawPath(tendrilPath, BoSeason.stem, style = Stroke(width = 2.2f, cap = StrokeCap.Round))
    drawPath(stemPath, BoSeason.stem)
    drawPath(stemPath, ink, style = Stroke(width = 2.5f, join = StrokeJoin.Round))
}

/** Pumpkin ribs — draw after the body fill, before the outline. */
internal fun DrawScope.drawPumpkinRibs() {
    val rib = Stroke(width = 2.2f, cap = StrokeCap.Round)
    drawPath(ribLeft, BoSeason.pumpkinDeep.copy(alpha = 0.55f), style = rib)
    drawPath(ribRight, BoSeason.pumpkinDeep.copy(alpha = 0.55f), style = rib)
    // Soft highlight on the upper-left lobe
    drawCircle(Color.White.copy(alpha = 0.18f), radius = 4f, center = Offset(24f, 40f))
}

/** Level props that sit on Bo's head and would collide with the stem. */
internal fun levelWearsHat(level: Int?): Boolean = level == 7 || level == 8 || level == 9
