package live.localapp.local.platform

import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

actual fun hapticSelection() {
    if (isReduceMotionEnabled()) return
    val gen = UISelectionFeedbackGenerator()
    gen.prepare()
    gen.selectionChanged()
}

actual fun hapticImpact() {
    if (isReduceMotionEnabled()) return
    val gen = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    gen.prepare()
    gen.impactOccurred()
}

actual fun hapticImpactMedium() {
    if (isReduceMotionEnabled()) return
    val gen = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium)
    gen.prepare()
    gen.impactOccurred()
}

actual fun hapticImpactLight() {
    if (isReduceMotionEnabled()) return
    val gen = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    gen.prepare()
    gen.impactOccurred()
}

actual fun hapticNotifySuccess() {
    if (isReduceMotionEnabled()) return
    val gen = UINotificationFeedbackGenerator()
    gen.prepare()
    gen.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
}

actual fun hapticNotifyError() {
    if (isReduceMotionEnabled()) return
    val gen = UINotificationFeedbackGenerator()
    gen.prepare()
    gen.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
}

actual fun isReduceMotionEnabled(): Boolean = UIAccessibilityIsReduceMotionEnabled()
