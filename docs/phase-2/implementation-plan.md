# Phase 2 — Minimal Core Implementation Plan

1. Establish an independent, headless Kotlin domain core.
2. Implement a deterministic VPN connection state machine and reverse-order cleanup.
3. Integrate pinned AndroidLibXrayLite behind an `EngineAdapter`.
4. Integrate pinned Hev SOCKS5 Tunnel behind a replaceable `TunBridge`.
5. Persist profile metadata with DataStore and sensitive Xray JSON with Android Keystore AES-GCM.
6. Build Android `VpnService` TUN, DNS, route, and per-app policies.
7. Reconnect on validated physical-network changes with bounded backoff.
8. expose separate real Xray outbound and Hev TUN counters.
9. Validate the core with JVM tests, Android source compilation, and later headless device tests.

No UI, APK release, or ZIP packaging belongs to Phase 2.