plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.meshline.app"
    // Local SDK has android-36 installed -> target latest available.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.meshline.app"
        minSdk = 26            // Android 8.0, per spec
        targetSdk = 36         // latest SDK available in this dev env
        versionCode = 1
        versionName = "0.1.0-mvp"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    packaging {
        resources { excludes += "/META-INF/{DEPENDENCIES,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}" }
    }

    // Pin the BLE UUIDs in one place (see BleConstants in transport/BleUuids.kt
    // from Phase C). No INTERNET permission is declared in the manifest.
}

dependencies {
    // Compose BOM manages all Compose artifact versions.
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Coroutines / Flow are used through every layer (UI -> ViewModel -> Service -> Router).
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Crypto: BouncyCastle only (Ed25519 / X25519 primitives + ChaCha20-Poly1305).
    // NEVER roll our own primitives. Noise (noise-java) is added in Phase D for the
    // Noise_XX_25519_ChaChaPoly_SHA256 handshake; it is not needed for the codec.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Storage: Room runtime + SQLCipher are declared now so the version set is
    // stable, but entities/DAOs (and the KSP plugin they need) land in Phase D.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")

    testImplementation("junit:junit:4.13.2")
    // Real JSON impl for JVM unit tests (android.jar's org.json is stubbed).
    testImplementation("org.json:json:20250517")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
