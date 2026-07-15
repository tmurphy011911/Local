package live.localapp.local.platform

/**
 * Cross-platform haptic helpers for UI taps. Use [hapticSelection] for chip
 * / filter / sort taps (light tick), [hapticImpact] for confirm-style taps
 * (medium thump). No-op on unsupported platforms — never throws.
 *
 * The [hapticImpactMedium] / [hapticImpactLight] / [hapticNotifySuccess] /
 * [hapticNotifyError] variants back the Bo pull-to-refresh cue set (see
 * BoRefreshControl): medium on threshold-cross, light on release, success /
 * error on settle. Every helper below no-ops when the OS "reduce motion /
 * haptics" setting is on ([isReduceMotionEnabled]) so callers don't have to
 * gate each site.
 */
expect fun hapticSelection()

expect fun hapticImpact()

/** Medium impact — the "you can let go now" pull-to-refresh threshold cue. */
expect fun hapticImpactMedium()

/** Light impact — confirms a pull actually triggered a refresh. */
expect fun hapticImpactLight()

/** Success notification — a refresh resolved with fresh content. */
expect fun hapticNotifySuccess()

/** Error notification — a refresh failed. */
expect fun hapticNotifyError()

/**
 * True when the OS "reduce motion" (iOS) / animations-disabled (Android)
 * accessibility setting is active. UI should skip the Bo bounce/scale and
 * fall back to a static indicator; threshold + min-display timing are
 * unchanged. Also used to suppress haptics.
 */
expect fun isReduceMotionEnabled(): Boolean
