package live.localapp.local.feature.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser

/**
 * "Stubby Bo" — the mascot, rendered as a Compose vector from the canonical
 * SVG art (viewBox 0 0 100 100). Drawing it ourselves (rather than a static
 * drawable) lets the splash animate a real eye-blink and swap expressions.
 *
 * @param eyeOpen 1f = eyes fully open, 0f = closed. Drive this 1→~0→1 for a blink.
 * @param expression resting face. Default for the entrance, Happy/Excited once
 *   the greeting line lands.
 */
enum class BoExpression { Default, Happy, Excited }

private val BoCoral = Color(0xFFFF6B6B)
private val BoCoralDeep = Color(0xFFE85555)
private val BoInk = Color(0xFF1A1523)
private val BoPaper = Color(0xFFFAF7F2)

private const val BODY_D =
    "M50 22 C76 22 88 36 88 52 C88 64 86 72 86 76 Q82 80 78 78 Q74 82 70 78 " +
        "Q66 82 62 78 Q58 82 54 78 Q50 82 46 78 Q42 82 38 78 Q34 82 30 78 " +
        "Q26 82 22 78 Q18 80 14 76 C14 72 12 64 12 52 C12 36 24 22 50 22 Z"

private const val EYE_HAPPY_L_D = "M32 50 Q38 44 44 50"
private const val EYE_HAPPY_R_D = "M56 50 Q62 44 68 50"
private const val MOUTH_DEFAULT_D = "M44 64 Q50 68 56 64"
private const val MOUTH_HAPPY_D = "M42 62 Q50 70 58 62 Q54 68 46 68 Z"
private const val MOUTH_EXCITED_D = "M42 62 Q50 71 58 62 Q54 69 46 69 Z"

private fun svgPath(d: String): Path = PathParser().parsePathString(d).toPath()

@Composable
fun BoCharacter(
    modifier: Modifier = Modifier,
    expression: BoExpression = BoExpression.Default,
    eyeOpen: Float = 1f,
    showShadow: Boolean = true,
) {
    val bodyPath = remember { svgPath(BODY_D) }
    val eyeHappyL = remember { svgPath(EYE_HAPPY_L_D) }
    val eyeHappyR = remember { svgPath(EYE_HAPPY_R_D) }
    val mouthDefault = remember { svgPath(MOUTH_DEFAULT_D) }
    val mouthHappy = remember { svgPath(MOUTH_HAPPY_D) }
    val mouthExcited = remember { svgPath(MOUTH_EXCITED_D) }

    Canvas(modifier = modifier) {
        // Map the 100×100 viewBox onto the (square-ish) canvas, centered.
        val s = minOf(size.width, size.height) / 100f
        val tx = (size.width - 100f * s) / 2f
        val ty = (size.height - 100f * s) / 2f
        withTransform({
            translate(tx, ty)
            scale(s, s, pivot = Offset.Zero)
        }) {
            if (showShadow) {
                ellipse(50f, 88f, 26f, 3f, BoInk, alpha = 0.18f)
            }
            // Body
            drawPath(bodyPath, BoCoral)
            drawPath(
                bodyPath,
                BoInk,
                style = Stroke(width = 3.5f, join = StrokeJoin.Round),
            )
            // Cheeks
            ellipse(28f, 58f, 4.5f, 3f, BoCoralDeep, alpha = 0.7f)
            ellipse(72f, 58f, 4.5f, 3f, BoCoralDeep, alpha = 0.7f)

            // Eyes — blink collapses them vertically around their center line.
            when (expression) {
                BoExpression.Happy -> {
                    strokePath(eyeHappyL, BoInk, 3.5f)
                    strokePath(eyeHappyR, BoInk, 3.5f)
                }
                BoExpression.Default -> withTransform({
                    scale(1f, eyeOpen.coerceIn(0f, 1f), pivot = Offset(50f, 50f))
                }) {
                    ellipse(38f, 50f, 5.5f, 7f, BoInk)
                    ellipse(62f, 50f, 5.5f, 7f, BoInk)
                    ellipse(40f, 48f, 2.2f, 2.7f, BoPaper)
                    ellipse(64f, 48f, 2.2f, 2.7f, BoPaper)
                    drawCircle(BoPaper, radius = 0.9f, center = Offset(36f, 52f))
                    drawCircle(BoPaper, radius = 0.9f, center = Offset(60f, 52f))
                }
                BoExpression.Excited -> withTransform({
                    scale(1f, eyeOpen.coerceIn(0f, 1f), pivot = Offset(50f, 49f))
                }) {
                    ellipse(38f, 49f, 6f, 7.5f, BoInk)
                    ellipse(62f, 49f, 6f, 7.5f, BoInk)
                    ellipse(40f, 46f, 2.6f, 3.2f, BoPaper)
                    ellipse(64f, 46f, 2.6f, 3.2f, BoPaper)
                }
            }

            // Mouth
            when (expression) {
                BoExpression.Default -> strokePath(mouthDefault, BoInk, 2.5f)
                BoExpression.Happy -> {
                    drawPath(mouthHappy, BoCoralDeep)
                    drawPath(mouthHappy, BoInk, style = Stroke(width = 2.5f, join = StrokeJoin.Round))
                }
                BoExpression.Excited -> {
                    drawPath(mouthExcited, BoCoralDeep)
                    drawPath(mouthExcited, BoInk, style = Stroke(width = 2.5f, join = StrokeJoin.Round))
                }
            }
        }
    }
}

private fun DrawScope.ellipse(
    cx: Float,
    cy: Float,
    rx: Float,
    ry: Float,
    color: Color,
    alpha: Float = 1f,
) {
    drawOval(
        color = color,
        topLeft = Offset(cx - rx, cy - ry),
        size = Size(rx * 2, ry * 2),
        alpha = alpha,
    )
}

private fun DrawScope.strokePath(path: Path, color: Color, width: Float) {
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
