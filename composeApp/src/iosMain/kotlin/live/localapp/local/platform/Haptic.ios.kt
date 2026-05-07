package live.localapp.local.platform

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UISelectionFeedbackGenerator

actual fun hapticSelection() {
    val gen = UISelectionFeedbackGenerator()
    gen.prepare()
    gen.selectionChanged()
}

actual fun hapticImpact() {
    val gen = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    gen.prepare()
    gen.impactOccurred()
}
