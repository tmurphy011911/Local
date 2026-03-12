package live.localapp.local.di

import org.koin.core.context.startKoin

fun initKoin() {
    try {
        startKoin {
            modules(appModule)
        }
    } catch (_: Exception) {
        // Already started (e.g., Android activity recreation)
    }
}
