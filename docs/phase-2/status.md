# Phase 2 Status

## Implemented

- Independent native Kotlin source project with no UI activity.
- Pure Kotlin core contracts, models, policy validation, and connection state machine.
- Pinned AndroidLibXrayLite `v26.9.9` real adapter.
- Replaceable `TunBridge` contract and Hev SOCKS5 Tunnel `2.18.0` implementation.
- HevTun built from pinned source for API 24 and four Android ABIs.
- Android `VpnService` foreground lifecycle and reverse-order cleanup.
- Android Keystore AES-256-GCM profile configuration storage with DataStore metadata.
- Xray JSON validation, private local SOCKS inbound injection, and statistics policy.
- IPv4 full-tunnel route planning with LAN/custom CIDR bypass.
- Numeric TUN DNS policy and optional IPv6 route foundation.
- All-app, only-selected, and bypass-selected per-app routing.
- Validated physical-network monitoring and bounded reconnect state.
- Real Xray outbound and Hev TUN byte counters.
- Headless JVM tests, Android source compilation, merged manifest validation, and lint.

## Validation snapshot

- 12 unit tests pass with zero failures.
- Kotlin/Android modules compile against API 36.
- HevTun native code compiles for arm64-v8a, armeabi-v7a, x86, and x86_64 with API 24.
- App manifest merge succeeds with minSdk 24 and targetSdk 36.
- Android lint succeeds.
- No APK, ZIP, or UI was produced.

## Deferred device QA

No physical ADB device was available in the development environment. The
following checks are explicitly deferred to later device QA and have no
fabricated pass result:

- Run Android instrumentation tests for Keystore persistence and native library loading.
- Validate VPN consent and foreground-service startup.
- Validate real Xray → HevTun → Android TUN traffic.
- Test DNS leaks, Wi-Fi/cellular transitions, reconnect, sleep/wake, and clean shutdown.
- Validate IPv6 and OEM-specific lifecycle behavior.

Release compliance materials for LGPL-3.0 remain required before distribution.