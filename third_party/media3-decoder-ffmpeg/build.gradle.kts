// Vendored copy of androidx/media `libraries/decoder_ffmpeg` at tag 1.5.0
// (Apache-2.0, see LICENSE). Kept as close to upstream as possible; see README.md
// for the (few) local deviations and for how to produce the native library.
plugins {
    alias(libs.plugins.android.library)
}

// Architectures to build FFmpeg for. Overridable from the command line so a CI
// job can produce a subset (e.g. -PffmpegAbis=arm64-v8a,armeabi-v7a).
val ffmpegAbis: List<String> = (providers.gradleProperty("ffmpegAbis").orNull
    ?: "arm64-v8a,armeabi-v7a,x86,x86_64")
    .split(',')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        ndk { abiFilters.addAll(ffmpegAbis) }
        // Upstream's rules plus the reflection keep rules this app needs.
        consumerProguardFiles("consumer-proguard-rules.txt", "consumer-rules-vibe.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // FFmpeg's own sources and build output live in src/main/jni/ffmpeg (git-ignored).
    // The libraries this module actually ships are picked up from src/main/jniLibs/<abi>/,
    // where scripts/build-ffmpeg-decoder.sh puts them (AGP packages them as-is).
    sourceSets.getByName("main").jniLibs.srcDirs("src/main/jniLibs")

    lint {
        // Upstream sources carry media3's own lint expectations, not ours.
        abortOnError = false
    }
}

// Upstream turns the native build on as soon as src/main/jni/ffmpeg exists. Here the FFmpeg
// build is expected to run through scripts/build-ffmpeg-decoder.sh, which drops the finished
// libffmpegJNI.so into src/main/jniLibs/<abi>/ - that path needs no NDK inside Gradle and keeps
// a plain `./gradlew assembleDebug` working on every machine. Pass -PffmpegNative=true (with the
// FFmpeg tree present) to let Gradle/CMake compile the JNI layer instead.
val nativeBuildFromGradle = providers.gradleProperty("ffmpegNative").orNull?.toBoolean() == true
if (nativeBuildFromGradle) {
    if (!file("src/main/jni/ffmpeg").exists()) {
        throw GradleException(
            "-PffmpegNative=true, but src/main/jni/ffmpeg is missing. Run " +
                "scripts/build-ffmpeg-decoder.sh first (Linux/macOS + Android NDK), or build " +
                "without the property and use the prebuilt libraries in src/main/jniLibs/."
        )
    }
    android.externalNativeBuild.cmake {
        path = file("src/main/jni/CMakeLists.txt")
        // Should match cmake_minimum_required in the CMakeLists.
        version = "3.22.1"
    }
}

dependencies {
    implementation(libs.media3.decoder)
    implementation(libs.media3.exoplayer)
    implementation(libs.androidx.annotation)
    compileOnly(libs.checker.qual)
    // Upstream declares this compileOnly too; androidx.annotation 1.9 references
    // kotlin.annotations.jvm.MigrationStatus and javac warns without it.
    compileOnly(libs.kotlin.annotations.jvm)
}
