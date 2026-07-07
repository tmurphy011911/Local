import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Read keys from local.properties so they never get committed.
val localProps: Properties = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}
val mapsApiKey: String = localProps.getProperty("MAPS_API_KEY", "")
val sentryDsnAndroid: String = localProps.getProperty("SENTRY_DSN_ANDROID", "")
val sentryDsnIos: String = localProps.getProperty("SENTRY_DSN_IOS", "")
val postHogKey: String = localProps.getProperty("POSTHOG_KEY", "")
val postHogHost: String = localProps.getProperty("POSTHOG_HOST", "https://us.i.posthog.com")

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.browser)
            implementation(libs.ktor.client.okhttp)
            implementation("androidx.core:core-splashscreen:1.0.1")
            implementation("com.google.android.play:review-ktx:2.0.2")
            implementation("com.android.installreferrer:installreferrer:2.2")
            implementation("com.google.android.gms:play-services-location:21.3.0")
            implementation("com.revenuecat.purchases:purchases:8.12.1")
            implementation("com.revenuecat.purchases:purchases-ui:8.12.1")
            // Google Maps for the plan-detail map header (Android only —
            // iOS uses MapKit via PlanDetailView.swift).
            implementation("com.google.android.gms:play-services-maps:19.0.0")
            implementation("com.google.maps.android:maps-compose:6.1.2")
            // Observability
            implementation(libs.sentry.android)
            implementation(libs.posthog.android)
        }

        commonMain.dependencies {
            // Compose
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)

            // Lifecycle
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            // Kotlinx
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)

            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.encoding)
            implementation(libs.ktor.serialization.json)

            // Settings (local key-value storage)
            implementation(libs.multiplatform.settings)

            // Supabase
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)
            implementation(libs.supabase.realtime)

            // Koin
            implementation(libs.koin.core)

            // Coil
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
        }

        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "live.localapp.local"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "live.localapp.local"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Aligned with the iOS build number scheme so the two platforms don't
        // drift. Play requires versionCode to strictly increase per upload.
        // 102 = clean rebuild after pulling 101 (deep-link ANR fix + native
        // debug symbols). 104 = 1.1.0 test builds (clears iOS hotfix build 103).
        // 105 = creator collab reward surfacing fix (toast + REWARDS counts).
        versionCode = 105
        versionName = "1.1.0"
        // Used by AndroidManifest's <meta-data android:value="${MAPS_API_KEY}" />
        // and read at runtime by the Directions API client.
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        // App display name — overridden by the staging build type so the dev app
        // reads "Local (Dev)" next to the real "Local".
        manifestPlaceholders["appLabel"] = "Local"
        // Default: not the staging (.dev) build. Overridden in the staging type.
        buildConfigField("boolean", "IS_STAGING", "false")
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            // NOTE: Play warns about missing native debug symbols for the .so
            // libs (Sentry's libsentry.so etc.). We don't set ndk.debugSymbolLevel
            // because that requires the NDK to be installed and would fail the
            // build. Those native libs are Sentry's, symbolicated via Sentry's
            // own pipeline — so Play's symbol warning is safe to ignore.
        }
        // Staging: the side-by-side dev app. Release-like, but its own app id
        // (live.localapp.local.dev) so it installs ALONGSIDE the real app, and
        // locked to the dev backend (IS_STAGING=true → Config.backendEnv=DEV).
        // Debug-signed so `installStaging` works on a device without the upload
        // keystore; for Play internal-testing, generate a signed bundle via the
        // Android Studio GUI selecting this build type.
        create("staging") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Local (Dev)"
            buildConfigField("boolean", "IS_STAGING", "true")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

// Generate a commonMain Kotlin object holding observability keys read from
// local.properties. Lets shared code reference Sentry/PostHog credentials
// without committing them and without a separate Android-only BuildConfig.
val generateBuildKeys = tasks.register("generateBuildKeys") {
    val outDir = layout.buildDirectory.dir("generated/source/buildKeys")
    outputs.dir(outDir)
    val dsnAndroid = sentryDsnAndroid
    val dsnIos = sentryDsnIos
    val phKey = postHogKey
    val phHost = postHogHost
    // Declare the key values as inputs so editing local.properties invalidates
    // this task. Without these, Gradle treats the task as up-to-date whenever
    // the output dir exists, and a changed DSN/key silently won't take effect
    // until a clean. (This masked a wrong SENTRY_DSN_IOS during setup.)
    inputs.property("dsnAndroid", dsnAndroid)
    inputs.property("dsnIos", dsnIos)
    inputs.property("phKey", phKey)
    inputs.property("phHost", phHost)
    doLast {
        val pkgDir = outDir.get().asFile.resolve("live/localapp/local/observability")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildKeys.kt").writeText(
            """
            package live.localapp.local.observability

            internal object BuildKeys {
                const val SENTRY_DSN_ANDROID: String = "$dsnAndroid"
                const val SENTRY_DSN_IOS: String = "$dsnIos"
                const val POSTHOG_KEY: String = "$phKey"
                const val POSTHOG_HOST: String = "$phHost"
            }
            """.trimIndent() + "\n"
        )
    }
}

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generateBuildKeys)
}

// Make sure the generator runs before any Kotlin compile.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    dependsOn(generateBuildKeys)
}
