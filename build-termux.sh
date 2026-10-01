#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

cd "$(dirname "$0")"

: "${PREFIX:=/data/data/com.termux/files/usr}"
export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-21-openjdk}"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
AAPT2="${ANDROID_AAPT2:-$PREFIX/bin/aapt2}"

if [[ ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "JDK 21 not found at $JAVA_HOME" >&2
  echo "Install Termux openjdk-21 or set JAVA_HOME." >&2
  exit 2
fi
if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "Android SDK platform 36 not found: $ANDROID_JAR" >&2
  echo "Install an ARM64-compatible Android SDK platform 36 and set ANDROID_HOME." >&2
  exit 2
fi
if [[ ! -x "$AAPT2" ]]; then
  echo "ARM64 aapt2 not found: $AAPT2" >&2
  echo "Install the Termux aapt2 package or set ANDROID_AAPT2." >&2
  exit 2
fi

# local.properties is machine-local and intentionally excluded from the ZIP.
printf 'sdk.dir=%s\n' "${ANDROID_HOME//\\/\\\\}" > local.properties
chmod +x gradlew

./gradlew --no-daemon \
  -Pandroid.aapt2FromMavenOverride="$AAPT2" \
  :app:assembleDebug

echo
echo "APK: $PWD/app/build/outputs/apk/debug/app-debug.apk"