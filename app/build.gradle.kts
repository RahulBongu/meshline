import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.VariantOutputConfiguration

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release-shaped (minified + shrunk) but signed with the local DEBUG
        // keystore so the release smoke test can be installed without manual
        // signing. NEVER use a real release key here.
        create("releaseSmoke") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
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

    // ABI splits are configured once, then selected per variant below:
    //  - release / releaseSmoke: only arm64-v8a + armeabi-v7a, NO universal
    //    APK, so each artifact stays well under the 25 MB budget.
    //  - debug: one universal APK with every ABI, so it also installs on an
    //    x86_64 emulator for UI/DB tests (BLE itself still needs real phones).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    packaging {
        resources { excludes += "/META-INF/{DEPENDENCIES,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}" }
        jniLibs { useLegacyPackaging = true } // SQLCipher .so layout on older APIs
    }
}

configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains:annotations:23.0.0")
    }
}

/**
 * Per-variant ABI output selection (see the `splits` block above).
 * This keeps the outcome independent of which tasks were requested on the
 * command line: asking for `assembleDebug` and `assembleReleaseSmoke` in the
 * same invocation must not strip the emulator-friendly universal debug APK.
 */
androidComponents {
    onVariants { variant ->
        val isDebugBuild = variant.buildType == "debug"
        variant.outputs.forEach { output ->
            val abis = output.filters
                .filter { it.filterType == FilterConfiguration.FilterType.ABI }
                .map { it.identifier }
            when {
                // Debug: keep only the universal APK.
                isDebugBuild && output.outputType == VariantOutputConfiguration.OutputType.ONE_OF_MANY ->
                    output.enabled.set(false)
                // Release-shaped: no universal APK, no emulator ABIs.
                !isDebugBuild && output.outputType == VariantOutputConfiguration.OutputType.UNIVERSAL ->
                    output.enabled.set(false)
                !isDebugBuild && abis.any { it == "x86_64" || it == "x86" } ->
                    output.enabled.set(false)
            }
        }
    }
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
    // room-compiler is only needed by KSP; never put it on the app classpath.
    // Maintained SQLCipher for Android (replaces the EOL android-database-sqlcipher).
    implementation("net.zetetic:sqlcipher-android:4.20.0")
    implementation("com.google.zxing:core:3.5.3") {
        exclude(group = "com.intellij", module = "annotations")
    }
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("com.google.guava:guava:32.1.2-android")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    ksp("androidx.room:room-compiler:2.6.1") {
        exclude(group = "com.intellij", module = "annotations")
    }

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    implementation("org.jetbrains:annotations:23.0.0")
    // Real JSON impl for JVM unit tests (android.jar's org.json is stubbed).
    testImplementation("org.json:json:20250517")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
