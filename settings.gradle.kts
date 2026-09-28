pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VibePlayer"
include(":app")

// Media3's FFmpeg audio decoder extension is not published to Google's Maven
// repository (upstream issue #2781), so the official 1.5.0 module is vendored
// under third_party/ and built as a local library. See its README.
include(":media3-decoder-ffmpeg")
project(":media3-decoder-ffmpeg").projectDir = file("third_party/media3-decoder-ffmpeg")
