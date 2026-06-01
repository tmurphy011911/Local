package live.localapp.local.ui.character

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing

/**
 * The Bo motion library. Each motion is a triggerable full-body animation
 * driven by a single 0..1 progress value (see [motionTransform]).
 *
 * Mapping from React prototype:
 *  Spin (z-rot 360°) · Flip (vertical flip + back) · Dance (sway+bob) ·
 *  Jump (anticipation+leap+land) · Wobble (jelly) · BarrelRoll (scaleX fake-3D) ·
 *  Nod (two yes-nods) · Shake (dizzy shimmy) · Tumble (roll across+back) ·
 *  PopIn (scale from zero with overshoot).
 */
enum class BoMotion {
    Spin, Flip, Dance, Jump, Wobble, BarrelRoll, Nod, Shake, Tumble, PopIn;

    fun durationMs(): Int = when (this) {
        Spin       -> 1100
        Flip       -> 1600
        Dance      -> 1600
        Jump       -> 1200
        Wobble     -> 1200
        BarrelRoll -> 1200
        Nod        -> 1100
        Shake      -> 1000
        Tumble     -> 1800
        PopIn      -> 900
    }

    fun easing(): Easing = when (this) {
        Spin       -> CubicBezierEasing(0.5f, 0f, 0.3f, 1f)
        Flip       -> CubicBezierEasing(0.4f, 0f, 0.3f, 1f)
        Dance      -> CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
        Jump       -> CubicBezierEasing(0.4f, 0f, 0.4f, 1f)
        Wobble     -> CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
        BarrelRoll -> CubicBezierEasing(0.4f, 0f, 0.4f, 1f)
        Nod        -> CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
        Shake      -> CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
        Tumble     -> CubicBezierEasing(0.45f, 0f, 0.55f, 1f)
        PopIn      -> CubicBezierEasing(0.34f, 1.7f, 0.64f, 1f)
    }
}

/**
 * Full motion transform — every channel a motion might modulate.
 * `scaleX`/`scaleY` pivot at (50, 80) to match Bo's bounce origin (feet).
 * `rotation` pivots at (50, 50) — Bo's geometric centre.
 */
data class MotionTransform(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val rotationDeg: Float = 0f,
) {
    companion object { val Identity = MotionTransform() }
}

/** Linear interpolation. */
private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Compute the [MotionTransform] for [motion] at progress [t] (0..1).
 *
 * Curves are hand-tuned from the React prototype's keyframes
 * (see `bo-motion-library` artifact) and mirror them frame-for-frame as
 * close as a single progress value allows.
 */
fun motionTransform(motion: BoMotion, t: Float): MotionTransform {
    if (t <= 0f) return MotionTransform.Identity
    return when (motion) {
        // 1 — Spin: -25° anticipation, then full 360°.
        BoMotion.Spin -> {
            val rot = if (t < 0.15f) lerp(0f, -25f, t / 0.15f)
                      else lerp(-25f, 360f, (t - 0.15f) / 0.85f)
            MotionTransform(rotationDeg = rot)
        }

        // 2 — Flip: pause, flip onto head, hold, flip back.
        BoMotion.Flip -> {
            when {
                t < 0.15f -> {
                    val k = t / 0.15f
                    MotionTransform(scaleY = 1f - 0.1f * k, dy = 4f * k)
                }
                t < 0.40f -> {
                    val k = (t - 0.15f) / 0.25f
                    MotionTransform(rotationDeg = lerp(0f, 180f, k), dy = lerp(4f, -10f, k))
                }
                t < 0.60f -> MotionTransform(rotationDeg = 180f, dy = lerp(-10f, 0f, (t - 0.40f) / 0.20f))
                t < 0.85f -> {
                    val k = (t - 0.60f) / 0.25f
                    MotionTransform(rotationDeg = lerp(180f, 360f, k), dy = lerp(0f, -6f, k))
                }
                else -> {
                    val k = (t - 0.85f) / 0.15f
                    MotionTransform(rotationDeg = 360f, dy = lerp(-6f, 0f, k))
                }
            }
        }

        // 3 — Dance: rhythmic sway + bob.
        BoMotion.Dance -> {
            // Four sway peaks across the run.
            val phase = t * 4f * kotlin.math.PI.toFloat()
            val rot = -10f * kotlin.math.sin(phase * 1f)
            val dx = -6f * kotlin.math.sin(phase * 0.5f)
            val dy = -2f * kotlin.math.abs(kotlin.math.cos(phase))
            // Ease out near the end so it settles instead of mid-step.
            val damp = if (t > 0.9f) 1f - (t - 0.9f) / 0.1f else 1f
            MotionTransform(rotationDeg = rot * damp, dx = dx * damp, dy = dy * damp)
        }

        // 4 — Jump: anticipation squash → leap (squash inverted) → land squash.
        BoMotion.Jump -> when {
            t < 0.15f -> {
                val k = t / 0.15f
                MotionTransform(scaleY = lerp(1f, 0.8f, k), scaleX = lerp(1f, 1.15f, k), dy = 6f * k)
            }
            t < 0.35f -> {
                val k = (t - 0.15f) / 0.20f
                MotionTransform(scaleY = lerp(0.8f, 1.15f, k), scaleX = lerp(1.15f, 0.9f, k), dy = lerp(6f, -32f, k))
            }
            t < 0.55f -> MotionTransform(scaleY = 1.1f, scaleX = 0.92f, dy = -32f)
            t < 0.75f -> {
                val k = (t - 0.55f) / 0.20f
                MotionTransform(scaleY = lerp(1.1f, 0.82f, k), scaleX = lerp(0.92f, 1.14f, k), dy = lerp(-32f, 0f, k))
            }
            else -> {
                val k = (t - 0.75f) / 0.25f
                MotionTransform(scaleY = lerp(0.82f, 1f, k), scaleX = lerp(1.14f, 1f, k))
            }
        }

        // 5 — Wobble: jelly jiggle, settles.
        BoMotion.Wobble -> {
            val damp = 1f - t
            val phase = t * 5f * kotlin.math.PI.toFloat()
            val rot = 6f * kotlin.math.sin(phase) * damp
            val sx = 1f + 0.06f * kotlin.math.cos(phase) * damp
            val sy = 1f - 0.06f * kotlin.math.cos(phase) * damp
            MotionTransform(scaleX = sx, scaleY = sy, rotationDeg = rot)
        }

        // 6 — Barrel Roll: fake Y-axis rotation via scaleX squash-and-flip.
        BoMotion.BarrelRoll -> when {
            t < 0.25f -> MotionTransform(scaleX = lerp(1f, 0.1f, t / 0.25f))
            t < 0.50f -> MotionTransform(scaleX = lerp(-0.1f, -1f, (t - 0.25f) / 0.25f))
            t < 0.75f -> MotionTransform(scaleX = lerp(-1f, -0.1f, (t - 0.50f) / 0.25f))
            else      -> MotionTransform(scaleX = lerp(0.1f, 1f, (t - 0.75f) / 0.25f))
        }

        // 7 — Nod: two quick downward yes-nods.
        BoMotion.Nod -> when {
            t < 0.20f -> {
                val k = t / 0.20f
                MotionTransform(rotationDeg = 8f * k, dy = 3f * k)
            }
            t < 0.40f -> {
                val k = (t - 0.20f) / 0.20f
                MotionTransform(rotationDeg = lerp(8f, 0f, k), dy = lerp(3f, 0f, k))
            }
            t < 0.60f -> {
                val k = (t - 0.40f) / 0.20f
                MotionTransform(rotationDeg = 8f * k, dy = 3f * k)
            }
            t < 0.80f -> {
                val k = (t - 0.60f) / 0.20f
                MotionTransform(rotationDeg = lerp(8f, 0f, k), dy = lerp(3f, 0f, k))
            }
            else -> MotionTransform.Identity
        }

        // 8 — Shake: fast horizontal shimmy, decays toward the end.
        BoMotion.Shake -> {
            val phase = t * 6f * kotlin.math.PI.toFloat()
            val damp = 1f - t * 0.5f
            val dx = 6f * kotlin.math.sin(phase) * damp
            val rot = 3f * kotlin.math.sin(phase) * damp
            MotionTransform(dx = dx, rotationDeg = rot)
        }

        // 9 — Tumble: roll left + 360°, then right + -360°, back to centre.
        BoMotion.Tumble -> when {
            t < 0.30f -> {
                val k = t / 0.30f
                MotionTransform(dx = -40f * k, rotationDeg = -180f * k)
            }
            t < 0.35f -> MotionTransform(dx = -40f, rotationDeg = -180f)
            t < 0.65f -> {
                val k = (t - 0.35f) / 0.30f
                MotionTransform(dx = lerp(-40f, 40f, k), rotationDeg = lerp(-180f, 180f, k))
            }
            t < 0.70f -> MotionTransform(dx = 40f, rotationDeg = 180f)
            else -> {
                val k = (t - 0.70f) / 0.30f
                MotionTransform(dx = lerp(40f, 0f, k), rotationDeg = lerp(180f, 0f, k))
            }
        }

        // 10 — Pop In: scale from zero with rotation, overshoot, settle.
        BoMotion.PopIn -> when {
            t < 0.60f -> {
                val k = t / 0.60f
                MotionTransform(scaleX = 1.2f * k, scaleY = 1.2f * k, rotationDeg = lerp(-30f, 8f, k))
            }
            t < 0.80f -> {
                val k = (t - 0.60f) / 0.20f
                MotionTransform(scaleX = lerp(1.2f, 0.94f, k), scaleY = lerp(1.2f, 0.94f, k), rotationDeg = lerp(8f, -3f, k))
            }
            else -> {
                val k = (t - 0.80f) / 0.20f
                MotionTransform(scaleX = lerp(0.94f, 1f, k), scaleY = lerp(0.94f, 1f, k), rotationDeg = lerp(-3f, 0f, k))
            }
        }
    }
}

/** Used by [Bo] when no motion is active but a tap-bounce is. Same shape as the
 *  React prototype's bounceTransform but expressed via [MotionTransform]. */
internal fun bounceTransform(t: Float): MotionTransform {
    if (t <= 0f) return MotionTransform.Identity
    return when {
        t < 0.3f -> {
            val k = t / 0.3f
            MotionTransform(scaleX = 1f, scaleY = 1f + 0.04f * k, dy = -6f * k)
        }
        t < 0.6f -> {
            val k = (t - 0.3f) / 0.3f
            MotionTransform(scaleX = 1f, scaleY = 1.04f - 0.08f * k, dy = lerp(-6f, 2f, k))
        }
        else -> {
            val k = (t - 0.6f) / 0.4f
            MotionTransform(scaleX = 1f, scaleY = 0.96f + 0.04f * k, dy = lerp(2f, 0f, k))
        }
    }
}

/** Keep a stable name even though [BoMotions]' easing is private. */
internal val DefaultBounceEasing: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
internal val LinearMotionEasing: Easing = LinearEasing
