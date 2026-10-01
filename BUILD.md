# Building TO Client

The project includes the Gradle 9.8 Wrapper, pinned Android Gradle Plugin and
Kotlin versions, the Xray Android AAR, and prebuilt HevTun libraries for all
four supported ABIs. An Android NDK is **not** required for a normal build.

## Standard desktop build

### Prerequisites

- JDK 21
- Android SDK Platform 36
- Internet access for the first Gradle dependency download
- `ANDROID_HOME` or `local.properties` pointing to the Android SDK

The Android SDK and JDK are machine tools and cannot be bundled with the source
archive.

### Command

From the extracted project directory:

```bash
./gradlew --no-daemon :app:assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

This command creates a locally installable APK signed with Gradle's generated
debug key. A private production signing key is intentionally not bundled.

## Termux on ARM64 Android

Google's Linux SDK command-line tools and Maven `aapt2` binary are normally
x86-64. A native ARM64 Termux build therefore requires:

- Termux JDK 21 (`openjdk-21`)
- Termux ARM64 `aapt2`
- an ARM64-compatible Android SDK installation containing
  `platforms/android-36/android.jar`
- `ANDROID_HOME` set to that SDK directory
- Internet access on the first build

The NDK is not needed because the validated HevTun libraries are included under
`tun-hev/src/main/jniLibs`. Xray's four native libraries are included in the
pinned AAR under `app/libs`.

After those prerequisites are installed:

```bash
chmod +x build-termux.sh && ./build-termux.sh
```

The script validates the JDK, SDK platform and ARM64 `aapt2`, writes the
machine-local `local.properties`, and builds the debug APK.

If JDK, SDK, or `aapt2` use non-default locations:

```bash
JAVA_HOME=/path/to/jdk21 \
ANDROID_HOME=/path/to/android-sdk \
ANDROID_AAPT2=/path/to/aapt2 \
./build-termux.sh
```

## Validation commands

```bash
./gradlew --no-daemon \
  :core:test \
  :android-core:testDebugUnitTest \
  :android-core:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin \
  :app:processDebugMainManifest \
  :app:lintDebug
```

Instrumentation and physical VPN behavior require an Android device and remain
deferred until device QA is performed.

## Included native ABIs

- `arm64-v8a`
- `armeabi-v7a`
- `x86`
- `x86_64`

Verify bundled artifacts from the project root:

```bash
(cd app/libs && sha256sum -c ../../third_party/artifact-checksums.sha256)
sha256sum -c third_party/prebuilt-native-checksums.sha256
```