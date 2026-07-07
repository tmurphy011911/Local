package live.localapp.local.platform

import live.localapp.local.ActivityProvider
import live.localapp.local.BuildConfig

actual fun getAppVersion(): String {
    // Prefer PackageManager so we report exactly what's installed (handles
    // overrides from APK splits / Play install). Fall back to BuildConfig
    // if no Activity is registered (e.g. very early startup).
    val activity = ActivityProvider.activity
    if (activity != null) {
        try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            val versionName = pInfo.versionName ?: BuildConfig.VERSION_NAME
            val build = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            return "Local v$versionName (Build $build)"
        } catch (t: Throwable) {
            // fall through to BuildConfig
        }
    }
    return "Local v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
}

actual fun getMarketingVersion(): String {
    val activity = ActivityProvider.activity
    if (activity != null) {
        try {
            val pInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            return pInfo.versionName ?: BuildConfig.VERSION_NAME
        } catch (_: Throwable) { /* fall through */ }
    }
    return BuildConfig.VERSION_NAME
}
