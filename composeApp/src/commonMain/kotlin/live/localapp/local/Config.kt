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

    /**
     * Daily Local — the daily state-scoped trivia quiz that shares the Home
     * carousel with the leaderboard. When false the quiz card + sheet are dead
     * code (eliminated in release). Backed by get_daily_quiz / submit_quiz.
     */
    const val FEATURE_DAILY_QUIZ_ENABLED = true

    /**
     * OneSignal application id — a PUBLIC identifier, safe to ship in the binary
     * (like the Supabase anon keys and RevenueCat public keys above). Both
     * platforms init the OneSignal SDK with it. The REST API key is deliberately
     * NOT here: it is server-side only, read from the Supabase `secrets` table
     * by the push-send scripts, never embedded in the client.
     */
    const val ONESIGNAL_APP_ID = "bdbdc7ed-00db-4961-9ad7-67ec3a5cba9f"

    /**
     * Build-time gate for the Bo mascot pull-to-refresh (replaces the generic
     * Material3 spinner on the Home / Explore / Plans / Profile feeds). When
     * false, [AppRefreshBox] falls back to the stock [PullToRefreshBox] spinner.
     *
     * DEV-ONLY while the v1.3.0 (Material3-engine + Bo-overlay) rewrite is being
     * device-tested: ON in DEBUG + STAGING (`.dev`) builds, OFF in a plain
     * RELEASE — so v1.2.0 ships the stock spinner while dev builds exercise Bo.
     * `isDebugBuild`/`isStagingBuild` both fold to a constant false in release, so
     * the elimination pass still drops the BoRefreshControl path from the shipped
     * binary. When the rewrite is signed off, switch this to `const val … = true`.
     */
    val FEATURE_BO_PTR_ENABLED: Boolean
        get() = live.localapp.local.platform.isDebugBuild ||
            live.localapp.local.platform.isStagingBuild

    /**
     * Gate for the Bo reasoning layer (intent classifier → deterministic routing).
     * See `docs/specs/bo-reasoning-layer.md`. Phase 1 is DARK in release and ON in
     * DEBUG + STAGING (`.dev`) builds, so prod ships unaffected while dev/staging
     * dogfoods it. `isDebugBuild`/`isStagingBuild` fold to a constant false in
     * release, so the classifier call is eliminated from the shipped binary.
     * Later phases move the rollout to PostHog flags (spec §13.4) with app_config
     * as the global kill switch; this build-time gate is the Phase-1 start.
     */
    val FEATURE_BO_REASONING_ENABLED: Boolean
        get() = live.localapp.local.platform.isDebugBuild ||
            live.localapp.local.platform.isStagingBuild

    // --- Backend environment (prod vs dev) -------------------------------------
    //
    // A RELEASE build (App Store / TestFlight / Play) ALWAYS talks to prod — the
    // [SUPABASE_URL]/[SUPABASE_ANON_KEY] getters return the prod values whenever
    // [isDebugBuild] is false, so a shipped binary can never be pointed at the dev
    // project (which has no real user data). There is no override for this.
    //
    // A DEBUG build defaults to dev and can be switched at runtime via the in-app
    // debug menu (tap the "Local" logo, debug builds only). The choice is
    // persisted across launches; switching takes effect on the next launch because
    // the Supabase client + repositories are built once at startup.
    //
    // Anon keys are public (they ship in the binary) — not secrets.
    private const val PROD_SUPABASE_URL = "https://blifubxslaucshmlphdc.supabase.co"
    private const val PROD_SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImJsaWZ1YnhzbGF1Y3NobWxwaGRjIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI3MjgyNjEsImV4cCI6MjA4ODMwNDI2MX0.xx-iy7kKhHviAjtjfXdxNHp4pmKU-in3efr2SkvWTf0"
    private const val DEV_SUPABASE_URL = "https://xbyqwekcanrzovytxmdv.supabase.co"
    private const val DEV_SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InhieXF3ZWtjYW5yem92eXR4bWR2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzI3MzY5ODksImV4cCI6MjA4ODMxMjk4OX0.iFNwDPHgHtyfhsXhRNoEIBsAtLr3is3GdYY_b3GRxlc"

    enum class BackendEnv { PROD, DEV }

    private const val BACKEND_ENV_KEY = "debug_backend_env"

    // Cached selection, resolved once from persisted settings on first read.
    private var cachedBackendEnv: BackendEnv? = null

    /**
     * Whether the build is allowed to switch backends at runtime (debug menu).
     * True for DEBUG and STAGING (the `.dev` test app). False for a plain RELEASE
     * — the real store app is hard-locked to prod and has no switch at all.
     */
    val backendSwitchable: Boolean
        get() = live.localapp.local.platform.isDebugBuild || live.localapp.local.platform.isStagingBuild

    /**
     * The active backend, resolved by build type:
     *  - plain RELEASE build (the real store app) → always PROD. Locked, no switch.
     *  - DEBUG and STAGING builds → switchable via the debug menu; default DEV.
     *    (Staging is the `.dev` side-by-side app; pointing it at prod lets testers
     *    hit live data, still as a separate app from the real one.)
     */
    val backendEnv: BackendEnv
        get() {
            if (!backendSwitchable) return BackendEnv.PROD
            cachedBackendEnv?.let { return it }
            val stored = com.russhwolf.settings.Settings().getStringOrNull(BACKEND_ENV_KEY)
            val env = if (stored == BackendEnv.PROD.name) BackendEnv.PROD else BackendEnv.DEV
            cachedBackendEnv = env
            return env
        }

    /**
     * Persist a backend choice. Effective in DEBUG and STAGING builds; a no-op in
     * a plain release (the real store app is locked to prod).
     */
    fun setBackendEnv(env: BackendEnv) {
        if (!backendSwitchable) return
        cachedBackendEnv = env
        com.russhwolf.settings.Settings().putString(BACKEND_ENV_KEY, env.name)
    }

    val SUPABASE_URL: String
        get() = if (backendEnv == BackendEnv.DEV) DEV_SUPABASE_URL else PROD_SUPABASE_URL

    val SUPABASE_ANON_KEY: String
        get() = if (backendEnv == BackendEnv.DEV) DEV_SUPABASE_ANON_KEY else PROD_SUPABASE_ANON_KEY

    // --- Dev auth (debug build + dev backend only) -----------------------------
    //
    // When a DEBUG build is pointed at DEV, auth is fully self-serve for testing:
    //   1. Any 6-digit OTP is accepted (the code is ignored).
    //   2. Each phone number maps to its OWN persisted dev account
    //      (sign-in-or-sign-up on `dev+<digits>@localapp.live`), so onboarding can
    //      be exercised end-to-end and a returning phone is recognized as existing.
    //   3. [DEV_EXISTING_USER_PHONE] is pre-seeded with a completed profile on dev,
    //      so it always lands in the existing-user (skip-onboarding) flow.
    //
    // Requires the DEV project to have email auto-confirm ON so client sign-ups
    // yield an active session immediately. Keyed purely on the resolved backend,
    // so it's also on for a STAGING build (the .dev app testers install) — and can
    // NEVER be true on the real prod app, whose backend is always PROD.
    val devAuthActive: Boolean
        get() = backendEnv == BackendEnv.DEV

    /** Reserved dev phone pre-seeded as an existing user (skips onboarding). */
    const val DEV_EXISTING_USER_PHONE = "5555550100"

    /** The dev account email a phone number maps to (one persisted user per number). */
    fun devAuthEmailFor(phone: String): String = "dev+${phone.filter { it.isDigit() }}@localapp.live"

    // Ticketmaster sync runs server-side (daily GitHub Action); the key lives in
    // CI secrets, never the client. Do not re-add it here — it ships in the binary.
}
