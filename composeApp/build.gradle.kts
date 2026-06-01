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
            implementation(libs.ktor.client.okhttp)
            implementation("androidx.core:core-splashscreen:1.0.1")
            implementation("com.google.android.play:review-ktx:2.0.2")
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
        versionCode = 3
        versionName = "1.0.2"
        // Used by AndroidManifest's <meta-data android:value="${MAPS_API_KEY}" />
        // and read at runtime by the Directions API client.
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
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
