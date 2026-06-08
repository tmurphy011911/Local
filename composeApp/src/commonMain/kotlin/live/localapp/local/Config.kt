package live.localapp.local

object Config {
    /**
     * Developer intent flag: skip real OTP — any 6-digit code works.
     *
     * NEVER read this directly to branch behavior — use [testAuthBypassActive],
     * which additionally requires a debug binary so the bypass can NEVER run in
     * a release (App Store / TestFlight / Play) build even if this constant is
     * left true. The release toolchain folds [isDebugBuild] to a constant false,
     * letting dead-code elimination drop the bypass path (and the canned creds'
     * only reachable use) from the shipped binary.
     *
     * Instead of minting a local-only "test-<phone>" id (which has no Supabase
     * JWT and so gets a hard 401 from claude-proxy — Bo's only auth path now),
     * the bypass signs into the canned test account below to establish a REAL
     * session. Its user_profiles row carries the default 2-week trial, so the
     * proxy's entitlement gate passes. See PhoneAuthViewModel.verifyOtp.
     */
    const val TEST_AUTH_BYPASS = false

    /**
     * Effective bypass switch: the developer flag AND a debug binary. This is
     * the only thing call sites should branch on. Guarantees the OTP bypass +
     * canned-account sign-in are inert in any release build regardless of the
     * source-level [TEST_AUTH_BYPASS] value.
     */
    val testAuthBypassActive: Boolean
        get() = TEST_AUTH_BYPASS && live.localapp.local.platform.isDebugBuild

    /**
     * Canned email/password account used only when [testAuthBypassActive] (i.e.
     * TEST_AUTH_BYPASS true AND a debug binary). The bypass SIGNS IN to this
     * account to mint a real Supabase JWT — it never signs up. The account is
     * provisioned once, server-side, with its email pre-confirmed (the hosted
     * project has mailer_autoconfirm=false, so a client sign-up would create an
     * unconfirmed, unusable user). Not a real secret: a trial test user on the
     * shared project. These string constants are inert in release builds (the
     * only code path that reads them is gated behind isDebugBuild).
     */
    const val TEST_AUTH_EMAIL = "testbypass@localapp.live"
    const val TEST_AUTH_PASSWORD = "LocalTestBypass!2026"

    const val SUPABASE_URL = "https://blifubxslaucshmlphdc.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJsaWZ1YnhzbGF1Y3NobWxwaGRjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI3MjgyNjEsImV4cCI6MjA4ODMwNDI2MX0.xx-iy7kKhHviAjtjfXdxNHp4pmKU-in3efr2SkvWTf0"
    const val TICKETMASTER_API_KEY = "5l1bGU8HgMBDBB8OTKhja8MRtBBvSpGo"
}
