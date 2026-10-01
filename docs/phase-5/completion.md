# Final Integration and Delivery

Date: 2026-09-28

## Build readiness

- Gradle 9.8 Wrapper, AGP 9.4.1, Kotlin 2.4.20, API 36 configuration, Android
  resources, tests, and complete source are included.
- Xray 26.9.9 and HevTun 2.18.0 are pinned with checksums and license notices.
- Xray and HevTun binaries for `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`
  are included.
- HevTun's validated binaries are packaged from `jniLibs`; routine builds do
  not require the Android NDK. The pinned native source remains included.
- `build-termux.sh` supplies the ARM64 `aapt2` override and validates the JDK
  and Android SDK platform before building.
- `BUILD.md` contains the exact desktop and Termux commands and documents
  machine prerequisites that cannot be bundled.

## Final clean validation

- Clean debug APK assembly: passed.
- Core JVM tests: 34 passed, 0 failed.
- Android JVM tests: 1 passed, 0 failed.
- Android instrumentation source compilation: passed.
- Native Kotlin/UI compilation: passed.
- Manifest processing: passed.
- Android lint: passed.
- Xray artifact checksums: passed.
- HevTun four-ABI checksums: passed.
- Assembled APK inspected: Xray, HevTun, and DataStore native libraries were
  present for all four ABIs.

## Explicitly deferred

- Physical-device execution
- Instrumentation execution
- Real VPN consent and tunnel traffic on a device
- Wi-Fi/cellular, sleep/wake, DNS-leak, and IPv6 device behavior
- Production signing with the owner's private key

No deferred device result is claimed.