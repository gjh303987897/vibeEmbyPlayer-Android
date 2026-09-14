plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.vibeplayer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vibeplayer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release signing credentials are NEVER committed to this repository.
    // They are injected at build time from environment variables that the CI
    // workflow reads from encrypted GitHub Actions Secrets. Release builds are
    // REQUIRED to be signed.
    signingConfigs {
        create("release") {
            val storePath = System.getenv("KEYSTORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // AGP stops writing the v1 (JAR) signature block once minSdk >= 24, leaving
            // v2 only. A lot of on-device installers - OEM file managers, sideload tools,
            // some `pm install` paths - look at the JAR block first and then refuse the
            // APK as "no certificate / unsigned". Keep v1 + v2 + v3 on the shared debug
            // key so the same APK installs on every device.
            signingConfig?.apply {
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                // v4 exists only to speed up incremental ADB installs and needs a
                // matching .idsig next to the APK; standalone installers ignore it,
                // so keep the artifact self-contained instead of producing a sidecar.
                enableV4Signing = false
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only assign the signing config when credentials are present. When
            // they are missing we intentionally do NOT assign it, so AGP won't
            // fail on a missing storeFile; the assembleRelease doFirst check
            // below then fails the build with a clear message instead.
            if (!System.getenv("KEYSTORE_FILE").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Enforce release signing: fail a release build loudly when credentials are
// missing/incomplete, instead of silently producing an unsigned APK. This is
// attached to the actual release APK-producing task (assembleRelease and the
// release package tasks), so debug / PR builds are NOT affected. Using doFirst
// keeps the check at execution time and out of project configuration time.
tasks.configureEach {
    val n = name
    if (n == "assembleRelease" ||
        (n.startsWith("package") && n.contains("Release"))) {
        doFirst {
            val storePath = System.getenv("KEYSTORE_FILE")
            if (storePath.isNullOrBlank()) {
                throw GradleException(
                    "Release signing is required but KEYSTORE_FILE is not set. " +
                        "Provide it (and KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD) " +
                        "via GitHub Actions Secrets or env vars, then re-run."
                )
            }
            val password = System.getenv("KEYSTORE_PASSWORD")
            val alias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")
            if (password.isNullOrBlank() || alias.isNullOrBlank() || keyPassword.isNullOrBlank()) {
                throw GradleException(
                    "Release signing credentials are incomplete. Ensure " +
                        "KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD are all set."
                )
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AndroidX core & lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM + UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.datastore.preferences)

    // Security (EncryptedSharedPreferences for tokens)
    implementation(libs.androidx.security.crypto)

    // Coil
    implementation(libs.coil.compose)

    // Media3 (ExoPlayer)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    // Tests
    testImplementation(libs.junit)
    // Android provides org.json at runtime; JVM unit tests need its reference implementation.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
