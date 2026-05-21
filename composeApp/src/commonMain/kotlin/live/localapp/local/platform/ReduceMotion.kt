package live.localapp.local.platform

/**
 * True if the user has enabled the platform's "reduce motion" affordance.
 *   - iOS: Settings → Accessibility → Motion → Reduce Motion
 *   - Android: Settings → Developer / Accessibility "Remove animations"
 *     (sets ANIMATOR_DURATION_SCALE to 0)
 *
 * Read once at composition; fine for one-shot moments like splash. Surfaces
 * that need to react to runtime toggles should wrap this in a state reader.
 */
expect fun isReducedMotionEnabled(): Boolean
