package live.localapp.local.platform

/**
 * Cross-platform haptic helpers for UI taps. Use [hapticSelection] for chip
 * / filter / sort taps (light tick), [hapticImpact] for confirm-style taps
 * (medium thump). No-op on unsupported platforms — never throws.
 */
expect fun hapticSelection()

expect fun hapticImpact()
