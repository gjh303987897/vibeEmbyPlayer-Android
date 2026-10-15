# Windows Android build environment

The verified Windows toolchain is:

| Component | Version / path |
| --- | --- |
| JDK | Eclipse Temurin 17.0.20.1 (`temurin17-jdk`) |
| Gradle | Wrapper 8.10.2 |
| Android command-line tools | 22.0 (`android-clt`, Scoop package 15859902) |
| Android platform | `platforms;android-35` |
| Android Build Tools | `build-tools;35.0.0` and `build-tools;34.0.0` |
| Platform Tools | r37.0.1 |
| Android NDK | r26b, SDK directory `ndk/26.1.10909125` |
| CMake | 3.22.1 |
| Ninja | 1.10.2 |
| GNU Make | 4.4.1 |
| Yasm / NASM | 1.3.0 / 3.02 |

The JDK and Android command-line tools were installed with Scoop. SDK components were downloaded from Google's official repository and extracted into the Scoop-managed SDK after local DNS resolution prevented `sdkmanager` from downloading them. The SDK root in the verified setup is:

```text
%USERPROFILE%\scoop\apps\android-clt\current
```

## Install with Scoop

Open a new PowerShell window after installing Scoop, then run:

```powershell
scoop bucket add java https://github.com/ScoopInstaller/Java
scoop install temurin17-jdk android-clt make yasm nasm
```

Install the Android packages with the normal SDK manager command:

```powershell
sdkmanager --licenses
sdkmanager `
  "platform-tools" `
  "platforms;android-35" `
  "build-tools;34.0.0" `
  "build-tools;35.0.0" `
  "ndk;26.1.10909125" `
  "cmake;3.22.1"
```

The project uses the Gradle Wrapper, so a separate Gradle installation is unnecessary. If `sdkmanager` cannot resolve `dl.google.com`, fix the machine's DNS or configure the network proxy before running the command; do not hard-code a changing CDN address.

Scoop persists `JAVA_HOME` and `ANDROID_HOME`. Persist the NDK path for the project's native build script:

```powershell
[Environment]::SetEnvironmentVariable("ANDROID_NDK_HOME", "$env:USERPROFILE\scoop\apps\android-clt\current\ndk\26.1.10909125", "User")
```

Open a new terminal to inherit user environment changes. To configure the current PowerShell session and select the SDK's CMake 3.22.1 ahead of another installed CMake, run:

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\scoop\apps\temurin17-jdk\current"
$env:ANDROID_HOME = "$env:USERPROFILE\scoop\apps\android-clt\current"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_NDK_HOME = "$env:ANDROID_HOME\ndk\26.1.10909125"
$env:ANDROID_NDK_ROOT = $env:ANDROID_NDK_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\build-tools\35.0.0;$env:ANDROID_HOME\cmake\3.22.1\bin;$env:Path"
```

Verify the environment and build:

```powershell
java -version
sdkmanager --version
cmake --version
& "$env:ANDROID_NDK_HOME\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe" --version
.\gradlew.bat :app:assembleDebug --no-daemon
.\gradlew.bat :app:lintDebug --no-daemon
```

The Debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

The application build and the NDK ARM64 executable smoke check were exercised on this setup.
No Android emulator or system image is installed; Android playback and notification interaction
require a connected device or an installed emulator.

## Rebuild IJK native dependencies

Install Node.js 18.14 or newer, Git (including Git Bash), Ninja and a host Clang compiler in addition
to the tools above. Keep the SDK CMake directory ahead of other CMake installations on `Path`.

```powershell
node scripts/build-ijkplayer-native.mjs `
  --ndk "$env:ANDROID_NDK_HOME" `
  --bash "$env:USERPROFILE/scoop/apps/git/current/bin/bash.exe" `
  --jobs 8
```

Scoop's SDK directories can be junctions. If a tool cannot traverse the `current/ndk` junction,
use its real directory, such as `$env:USERPROFILE/scoop/persist/android-clt/ndk/26.1.10909125`.

The recipe pins IJKPlayer, FFmpeg, libxml2, libyuv and SoundTouch revisions; applies the distributed
NDK and subtitle patches; and builds all three native libraries with 16 KB ELF alignment. FFmpeg enables the
required AC-3/E-AC-3/DTS/TrueHD and subtitle decoders, Matroska/HLS/DASH demuxers and AES HLS crypto.
GPL and nonfree components remain disabled. HTTPS is handled by the app's OkHttp transport.
The subtitle patch and distributed support sources bridge decoded PGS/DVD/DVB bitmaps into the
Kotlin player without replacing the upstream Java AAR. Their hashes are recorded in native-build.json.

Sources and intermediates live under the ignored `build/ijk-native/` directory. Completed AARs,
feature configurations, hashes and license notices are written under `third_party/ijkplayer/`.
The original official ABI AARs are packaging inputs; Gradle includes only the rebuilt `-full.aar`
native variants. To resume selected architectures, pass `--abi x86,x86_64`; completed objects are
reused when configuration matches. See `THIRD_PARTY_LICENSES.md` for source and notice details.

After rebuilding native dependencies, run:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Device execution is optional for the coding deliverable. To exercise the existing AC-3/DTS device
regressions later, run `.\gradlew.bat :app:connectedDebugAndroidTest` on Android; these check native
decode/render events and advancing playback.

## Remove the environment

Uninstall the Scoop-managed tools when they are no longer needed:

```powershell
scoop uninstall temurin17-jdk android-clt make yasm nasm
[Environment]::SetEnvironmentVariable("ANDROID_NDK_HOME", $null, "User")
```

If the Java bucket is no longer used by another Scoop package, remove it with:

```powershell
scoop bucket rm java
```

`android-clt` persists SDK packages across normal Scoop upgrades. Remove the persisted SDK data only when it is intentionally no longer needed:

```powershell
Remove-Item -Recurse -Force "$env:USERPROFILE\scoop\persist\android-clt"
```
