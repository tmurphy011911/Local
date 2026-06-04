package live.localapp.local.observability

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

/**
 * Install a Kotlin/Native uncaught-exception hook so crashes thrown in shared
 * Kotlin code are reported to Sentry *with the Kotlin throwable's type/message*
 * before the process dies.
 *
 * Why this is needed: a Kotlin/Native uncaught exception ultimately raises
 * SIGABRT, which sentry-cocoa's signal handler does capture — but the resulting
 * crash report is a raw native stack with no Kotlin context, so it's nearly
 * useless for triage. This hook runs first, flattens the throwable into a
 * Sentry error event, force-flushes, then re-terminates so the native crash
 * still happens (preserving sentry-cocoa's own capture + a proper crash count).
 *
 * Call exactly once at app boot, right after the analytics bridge is installed.
 * Exposed to Swift as `IosCrashHandlerKt.installIosCrashHandler()`.
 */
@OptIn(ExperimentalNativeApi::class)
fun installIosCrashHandler() {
    setUnhandledExceptionHook { throwable ->
        try {
            Analytics.recordError(throwable, message = "Kotlin/Native uncaught exception")
            Analytics.flush()
        } catch (_: Throwable) {
            // Never let crash-reporting mask the original crash.
        }
        // Re-raise so the process still terminates the way it would have, and
        // sentry-cocoa's native signal handler also records it. (This does NOT
        // re-invoke the hook, so there's no recursion.)
        terminateWithUnhandledException(throwable)
    }
}
