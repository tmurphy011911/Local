package live.localapp.local.platform

import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

actual fun isReducedMotionEnabled(): Boolean = UIAccessibilityIsReduceMotionEnabled()
