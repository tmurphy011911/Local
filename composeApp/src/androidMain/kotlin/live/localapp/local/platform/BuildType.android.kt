package live.localapp.local.platform

import live.localapp.local.BuildConfig

actual val isDebugBuild: Boolean = BuildConfig.DEBUG

actual val isStagingBuild: Boolean = BuildConfig.IS_STAGING
