package live.localapp.local

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityExtraLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityLarge
import platform.UIKit.UIContentSizeCategoryAccessibilityMedium
import platform.UIKit.UIContentSizeCategoryDidChangeNotification
import platform.UIKit.UIContentSizeCategoryExtraExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryExtraExtraLarge
import platform.UIKit.UIContentSizeCategoryExtraLarge
import platform.UIKit.UIContentSizeCategoryExtraSmall
import platform.UIKit.UIContentSizeCategoryLarge
import platform.UIKit.UIContentSizeCategoryMedium
import platform.UIKit.UIContentSizeCategorySmall

fun MainViewController() = ComposeUIViewController {
    ScaledForDynamicType { App() }
}

// The app rendered at a fixed size on iOS — Compose does not wire UIKit's
// Dynamic Type into its own fontScale, so the system "Larger Text" slider did
// nothing and the baseline read small. This provider does both:
//   • BASE_TEXT_BUMP makes the whole app larger at the *default* slider (Part 1).
//   • the iOS content-size category scales it from there (Part 2).
// Only .sp text scales (font scale); .dp sizes (icons, fixed heights) do not —
// so genuinely fixed-height containers stay put while their text grows.

// Baseline enlargement at the default iOS text size. Tune this one number to
// make the app read bigger/smaller everywhere.
private const val BASE_TEXT_BUMP = 1.12f

// Ceiling on the Dynamic Type multiplier. iOS Accessibility sizes go up to
// ~3.5×, which would overflow fixed-height cards/buttons across the app. Cap it
// until a dedicated large-text layout pass lands; standard sizes are unaffected.
private const val MAX_DYNAMIC_SCALE = 1.35f

@Composable
private fun ScaledForDynamicType(content: @Composable () -> Unit) {
    var category by remember {
        mutableStateOf(UIApplication.sharedApplication.preferredContentSizeCategory)
    }
    // React to the user changing the system text size while the app is running.
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIContentSizeCategoryDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            category = UIApplication.sharedApplication.preferredContentSizeCategory
        }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }

    val base = LocalDensity.current
    val dynamicScale = contentSizeCategoryScale(category).coerceAtMost(MAX_DYNAMIC_SCALE)
    val scaled = Density(density = base.density, fontScale = base.fontScale * BASE_TEXT_BUMP * dynamicScale)
    CompositionLocalProvider(LocalDensity provides scaled) {
        content()
    }
}

// UIKit content-size category → multiplier relative to the "Large" default (1.0),
// mirroring UIFontMetrics' body scaling. Accessibility rows are listed for
// completeness but are clamped by MAX_DYNAMIC_SCALE above for now.
private fun contentSizeCategoryScale(category: String?): Float = when (category) {
    UIContentSizeCategoryExtraSmall -> 0.82f
    UIContentSizeCategorySmall -> 0.88f
    UIContentSizeCategoryMedium -> 0.94f
    UIContentSizeCategoryLarge -> 1.0f // iOS default
    UIContentSizeCategoryExtraLarge -> 1.12f
    UIContentSizeCategoryExtraExtraLarge -> 1.24f
    UIContentSizeCategoryExtraExtraExtraLarge -> 1.35f
    UIContentSizeCategoryAccessibilityMedium -> 1.6f
    UIContentSizeCategoryAccessibilityLarge -> 1.9f
    UIContentSizeCategoryAccessibilityExtraLarge -> 2.3f
    UIContentSizeCategoryAccessibilityExtraExtraLarge -> 2.8f
    UIContentSizeCategoryAccessibilityExtraExtraExtraLarge -> 3.5f
    else -> 1.0f
}
