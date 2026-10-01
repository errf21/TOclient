# Pinned Phase 2 native dependencies

## AndroidLibXrayLite

- Upstream: `https://github.com/2dust/AndroidLibXrayLite`
- Release: `v26.9.9`
- Artifact: `app/libs/libv2ray-26.9.9.aar`
- License: LGPL-3.0
- Role: Xray-core Android Go/JNI binding

## Hev SOCKS5 Tunnel

- Upstream: `https://github.com/heiher/hev-socks5-tunnel`
- Release: `2.18.0`
- Source: `third_party/hev-socks5-tunnel` (pinned tag 2.18.0)
- Packaged binaries: `tun-hev/src/main/jniLibs` for `arm64-v8a`,
  `armeabi-v7a`, `x86`, and `x86_64`
- Reproducible source build: NDK 29, `APP_PLATFORM=android-24`; source and
  Android makefiles remain under `third_party/hev-socks5-tunnel`
- License: MIT
- Role: initial replaceable TUN-to-SOCKS bridge

Xray artifact hashes are recorded in `artifact-checksums.sha256`. Packaged
HevTun hashes are recorded in `prebuilt-native-checksums.sha256`. These
dependencies are integrated through independent TO Client interfaces. They do
not include or reuse ZedSecure code or assets.
