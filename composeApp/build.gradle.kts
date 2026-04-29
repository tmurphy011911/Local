import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// Read MAPS_API_KEY from local.properties so the key never gets committed.
// Add a line like `MAPS_API_KEY=AIza...` to local.properties (gitignored).
// Used as a manifest placeholder for the Maps SDK and a BuildConfig field
// for the Directions API client.
val mapsApiKey: String = run {
    val props = Properties()
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
    props.getProperty("MAPS_API_KEY", "")
}

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
            implementation("com.google.android.play:review-ktx:2.0.2")
            implementation("com.google.android.gms:play-services-location:21.3.0")
            implementation("com.revenuecat.purchases:purchases:8.12.1")
            implementation("com.revenuecat.purchases:purchases-ui:8.12.1")
            // Google Maps for the plan-detail map header (Android only —
            // iOS uses MapKit via PlanDetailView.swift).
            implementation("com.google.android.gms:play-services-maps:19.0.0")
            implementation("com.google.maps.android:maps-compose:6.1.2")
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
            implementation(libs.ktor.serialization.json)

            // Settings (local key-value storage)
            implementation(libs.multiplatform.settings)

            // Supabase
            implementation(libs.supabase.postgrest)
            implementation(libs.supabase.auth)

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
        versionCode = 1
        versionName = "1.0"
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
