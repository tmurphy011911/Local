package live.localapp.local.platform

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform
import platform.Foundation.NSBundle

@OptIn(ExperimentalNativeApi::class)
actual val isDebugBuild: Boolean = Platform.isDebugBinary

// The Staging configuration's xcconfig sets IS_STAGING=YES, surfaced into the
// app's Info.plist. Absent (Debug/Release) → null → false.
actual val isStagingBuild: Boolean =
    (NSBundle.mainBundle.infoDictionary?.get("IS_STAGING") as? String) == "YES"
