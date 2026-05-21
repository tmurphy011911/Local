package live.localapp.local.platform

import platform.Foundation.NSBundle

actual fun getAppVersion(): String {
    val info = NSBundle.mainBundle.infoDictionary
    val version = info?.get("CFBundleShortVersionString") as? String ?: "?"
    val build = info?.get("CFBundleVersion") as? String ?: "?"
    return "Local v$version (Build $build)"
}
