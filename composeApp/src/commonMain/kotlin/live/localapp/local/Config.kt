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
     * Review-build escape hatch. When true, the OTP bypass is active even in a
     * RELEASE binary, so Google Play / App Store reviewers can sign in with any
     * 6-digit code (the normal [isDebugBuild] guard would otherwise lock them
     * out of the signed artifact and trigger a "Missing sign in details"
     * rejection).
     *
     * ⚠️ MUST be flipped back to false before promoting any build to
     * production. While true, the uploaded artifact contains a UNIVERSAL auth
     * bypass — anyone who enters any 6-digit OTP lands in the canned test
     * account. Only ship a true value to a closed review track, never to prod.
     */
    const val REVIEW_BUILD_BYPASS = false

    /**
     * Effective bypass switch: the developer flag AND (a debug binary OR the
     * review-build escape hatch). This is the only thing call sites should
     * branch on. With [REVIEW_BUILD_BYPASS] false, the OTP bypass + canned
     * account sign-in are inert in any release build regardless of the
     * source-level [TEST_AUTH_BYPASS] value.
     */
    val testAuthBypassActive: Boolean
        get() = TEST_AUTH_BYPASS &&
            (live.localapp.local.platform.isDebugBuild || REVIEW_BUILD_BYPASS)

    /**
     * Canned email/password account used only when [testAuthBypassActive] (i.e.
     * TEST_AUTH_BYPASS true AND a debug binary). The bypass SIGNS IN to this
     * account to mint a real Supabase JWT — it never signs up. The account is
     * provisioned once, server-side, with its email pre-confirmed (the hosted
     * project has mailer_autoconfirm=false, so a client sign-up would create an
     * unconfirmed, unusable user). Not a real secret: a trial test user on the
     * shared project. These string constants are inert in release builds (the
     * only code path that reads them is gated behind isDebugBuild).
     *
     * MULTI-ACCOUNT: this is the DEFAULT bypass account. [signInTestUser] will
     * instead use the entered email when it's a bypass-family address
     * (testbypass+a@localapp.live, testbypass+b@localapp.live, ...), letting a
     * two-device regression sign each device into a distinct user. Every such
     * account must be provisioned server-side the same way (email pre-confirmed)
     * and share [TEST_AUTH_PASSWORD]. Provisioning is NOT done by the client.
     */
    const val TEST_AUTH_EMAIL = "testbypass@localapp.live"
    const val TEST_AUTH_PASSWORD = "LocalTestBypass!2026"

    /**
     * Developer intent flag: under the test-auth bypass, route the canned account
     * through ONBOARDING on every sign-in instead of dropping straight to Home.
     *
     * The canned account already has a completed user_profiles row, so the normal
     * bypass path sees `hasProfile=true` and skips onboarding — which makes the
     * onboarding flow untestable via the bypass. When this is on, verifyOtp treats
     * the test sign-in as a brand-new user (no local profile recovered), so the
     * onboarding flow runs. On finish, savePreferences inserts a throwaway profile
     * row (id=null → server-generated); the auth session remains the canned
     * account, so Bo / claude-proxy entitlement still passes.
     *
     * Flip to false to test the returning-user → Home fast path instead. Gated on
     * [testForceOnboardingActive] so it can never affect a release build.
     */
    const val TEST_FORCE_ONBOARDING = true

    /** Effective force-onboarding switch: only when the bypass itself is active. */
    val testForceOnboardingActive: Boolean
        get() = TEST_FORCE_ONBOARDING && testAuthBypassActive

    /**
     * Master switch for the Local Leaderboard (home CTA + leaderboard sheet).
     * Build-time constant: flip to false before an App Store submission to hide
     * the feature from the shipped binary while it's still being built out. With
     * it false the home CTA item {} and the sheet route are dead code, so the
     * release toolchain's elimination pass drops the leaderboard UI entirely.
     * Unlike the auth bypasses above this is a real user feature — no isDebugBuild
     * guard; once it's ready you just ship it true.
     */
    const val FEATURE_LEADERBOARD_ENABLED = true

    const val SUPABASE_URL = "https://blifubxslaucshmlphdc.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJsaWZ1YnhzbGF1Y3NobWxwaGRjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI3MjgyNjEsImV4cCI6MjA4ODMwNDI2MX0.xx-iy7kKhHviAjtjfXdxNHp4pmKU-in3efr2SkvWTf0"
    // Ticketmaster sync runs server-side (daily GitHub Action); the key lives in
    // CI secrets, never the client. Do not re-add it here — it ships in the binary.
}
