# TO Client — Phase 1 Second-Pass ZedSecure Static APK Audit

## Evidence scope

- Artifact: ZedSecure XAPK 3.0.9, versionCode 29, package `com.zedsecure.vpn`
- Source: APKPure public download page
- XAPK SHA-256: `cc9cfea01e9a25188d40800eb96ac66394cd064407d7c8bb5d719feba2f92a80`
- Base APK SHA-256: `27dbf3ac0f0dce71fbf1771d3fdd17bde0ccfc4ae97894116e3714cb1c0400d3`
- Artifact is a mirrored Play split package with Play split/distribution-stamp metadata. It was not downloaded directly from Google Play, so byte identity with the currently served Play artifact is not proven.

Evidence labels: **DV** directly verified from artifact; **PD** public documentation/listing; **SI** strong inference; **U** unknown.

## A. Updated findings

### Package and platform

- **DV:** package `com.zedsecure.vpn`; versionName `3.0.9`; versionCode `29`.
- **DV:** minSdk 24, targetSdk 36, compileSdk/platform build 37.
- **DV:** analyzed split set contains base, `config.arm64_v8a`, English, and French splits. This proves ARM64 for this artifact only, not absence of other Play ABI variants.
- **DV:** app is Kotlin/native Android with Jetpack Compose/Compose Resources, not Flutter in this release. Main activity is `dev.cluvex.zedsecure.MainActivity`.
- **DV:** one DEX: 7,289 classes, 44,429 methods, 37,957 strings; application code is partly obfuscated.
- **DV:** Android Gradle Plugin metadata reports 9.3.2.

### Permissions and components

- **DV permissions:** INTERNET, network/Wi-Fi state, CHANGE_NETWORK_STATE, foreground service, foreground special use, notifications, boot completed, query all packages, camera, Play license check.
- **DV service:** `dev.cluvex.zedsecure.core.ZedVpnService`, non-exported, protected by `BIND_VPN_SERVICE`, foreground special-use subtype `vpn`.
- **DV:** `SubscriptionUpdateJobService`, Quick Settings `ZedTileService`, `BootReceiver`, `ZedWidgetProvider`, `Ikev2EventReceiver`, and QR scanner activity.
- **DV:** FileProvider, Compose resource provider, and AndroidX Startup provider.
- **DV:** `allowBackup=false`; native libraries are extracted.

### Engine/core inventory

- **DV:** `libgojni.so` is a 112,446,744-byte Go/JNI library exposing `libv2ray`, `libbox`, Psiphon, and MasterDNS bindings.
- **DV:** embedded Go modules include AndroidLibXrayLite, Xray-core `v1.260327.1-...5ca6f4b7d4dc`, sing-box `v1.14.0`, Psiphon tunnel core `v1.0.11-...ceb78316d06e`, sing-openvpn, sing-openconnect, sing-tun, WireGuard and AmneziaWG modules.
- **DV:** separate ARM64 binaries/libraries: Hev SOCKS5 tunnel, Zeptun/JNI, Tor, obfs4proxy, Snowflake, Conjure, DNSTT, OpenConnect, SNI spoof, and app guard library.
- **DV:** Tor identifies as `0.4.9.11-dev`; Zeptun identifies as `1.1.1`.
- **DV:** IKEv2 uses Android `VpnManager`, `Ikev2VpnProfile`, and platform IKE/IPsec APIs.
- **DV:** both HevTun and Zeptun are bundled, and `useZepTun` is observable; runtime bridge choice is configurable or conditional.

### Native libraries in the ARM64 split

`libandroidx.graphics.path.so`, `libconjure.so`, `libdatastore_shared_counter.so`, `libdnstt.so`, `libgojni.so`, `libhev-socks5-tunnel.so`, `libimage_processing_util_jni.so`, `libobfs4proxy.so`, `libopenconnect.so`, `libsnispoof.so`, `libsnowflake.so`, `libsurface_util_jni.so`, `libtor.so`, `libzedguard.so`, `libzeptun-jni.so`, `libzeptun.so`.

### Profiles, imports, and subscriptions

- **DV:** domain models include VPN, subscription, DNS tunnel, MasterDNS, OpenConnect, IKEv2, Psiphon, SSH, SNI spoof, proxy chain, cross-chain, Tor, sing-box, raw JSON, and sealed `.zsx` sources.
- **DV:** manifest accepts `.zsx`, generic shared octet streams, and schemes including `zedsecure`, `v2rayng`, `hiddify`, `sing-box`, `clash`, `clashmeta`, `v2raytun`, `streisand`, `happ`, `vless`, `vmess`, `trojan`, `ss`, `hysteria`, `hysteria2`, `hy2`, `wireguard`, `socks`, `socks5`, and `snispoof`.
- **DV:** DEX evidence confirms `.ovpn`, `.conf`, JSON, QR, clipboard, HTTP(S) subscriptions, Base64 subscriptions, and protocol-specific share links.
- **DV:** scheduled subscription updates use `JobScheduler`/`JobService`; exact retry/HTTP cache policy remains unknown.

### DNS, routing, lifecycle, and diagnostics

- **DV:** settings and code strings include DoH, DoT, plain DNS, FakeDNS, remote/direct resolvers, host overrides, routing rules, per-app VPN, LAN bypass, IPv6, kill switch, MTU, and proxy chains.
- **DV:** `ConnectivityManager.NetworkCallback`, reconnect states, network-change remeasurement, `onRevoke`, startForeground, notification channels, boot auto-connect, and deterministic stop hooks are present.
- **DV:** Rx/Tx counters, live upload/download rates, latency tests, throughput tests, and path-MTU measurement are implemented at code level.
- **U:** exact reconnect state machine, backoff constants in operational context, DNS-leak behavior, endpoint choices, and failure policy require runtime testing.

### Persistence and security

- **DV:** AndroidX DataStore Preferences 1.2.1 and SharedPreferences calls are bundled. No positive Room database indicator was found.
- **DV:** backup is disabled.
- **DV:** Bouncy Castle, Argon2/KDF-related code, `.zsx` crypto classes, and a native guard are present.
- **DV:** no standalone PEM/CRT/private-key asset was found. Generic PEM parsers and one RSA public-key-like Base64 blob are present in DEX; its purpose is not established.
- **U:** Android Keystore usage for profile secrets, exact at-rest schema, secret separation, migration behavior, and the purpose of the observable RSA key remain unresolved.
- **DV:** Google Play PairIP license-check classes, `CHECK_LICENSE`, and Play distribution-stamp metadata are present.

## B. Newly verified facts

1. The current audited app release is native Kotlin/Compose, not Flutter.
2. Exact package/version/SDK values and Android component declarations are known.
3. Xray, sing-box, Psiphon, Tor, OpenConnect, OpenVPN support modules, IKEv2 platform integration, DNS tunnels, HevTun, and Zeptun are materially bundled—not merely advertised.
4. Quick Settings, widget, boot receiver, subscription JobService, QR scanner, foreground VPN service, and IKEv2 event receiver exist.
5. DataStore, SharedPreferences, backup disablement, PairIP licensing, and Play split metadata exist.
6. Profile-source classes and import schemes confirm the broad profile/import surface.

## C. Previously unknown items now resolved

- VPN service class and manifest protection.
- Foreground-service special-use declaration.
- Main activity and major Android components.
- Native library inventory for ARM64.
- Exact analyzed SDK/package/version values.
- Presence and versions of major Go engine dependencies.
- Platform IKEv2 approach.
- Subscription scheduling mechanism.
- Compose/AndroidX dependency family and selected versions.
- Existence of boot, tile, widget, and QR integrations.

## D. Remaining unknowns

- Byte identity with the artifact currently served directly by Google Play.
- Availability and contents of other ABI variants.
- Full unobfuscated source and exact runtime engine-selection rules.
- Complete storage schema and Keystore/secret-at-rest behavior.
- Exact reconnection/backoff algorithm and network binding behavior.
- DNS leak resistance, kill-switch correctness, always-on/lockdown behavior, and IPv6 correctness on real devices.
- Exact endpoints for latency, speed, exit-IP, and geolocation diagnostics.
- Runtime stability/performance/battery behavior across Android/OEM versions.
- Full transitive license compliance and source-offer obligations of the distributed app.
- Whether all bundled engines are reachable in every region/build/configuration.

## E. Changes to recommended TO Client architecture

1. Keep the independent native Kotlin architecture; APK evidence strengthens—not originates—the choice.
2. Make the TUN bridge pluggable. Do not hard-code HevTun: the reference bundles both Hev and Zeptun.
3. Preserve strict engine adapters and separate platform-IKEv2 from app-hosted engines.
4. Isolate large Go cores behind a stable AAR/JNI boundary; the 112 MB uncompressed Go library demonstrates size and coupling risk.
5. Keep storage and secrets independent of engine JSON; use versioned domain models and explicit secret storage.
6. Keep scheduled subscription updates, boot behavior, tiles, and widgets outside the core VPN controller.
7. Do not copy the reference’s Compose resources, screens, strings, package structure, obfuscated code, or branded `.zsx` format.

## F. Changes to recommended engine strategy

- Xray-first remains a practical Phase 2 starting point.
- The prior assumption that sing-box integration was uncertain is resolved: a gomobile/libbox integration is present in the reference. TO Client must still make its own legal and technical decision.
- HevTun is confirmed, but Zeptun is also present; benchmark and select independently.
- Platform IKEv2 is confirmed as the practical strategy.
- Tor, Psiphon, OpenConnect/OpenVPN, and DNS tunnels are technically reproducible with established engines, but remain unsuitable for the first implementation increment because of licensing, binary size, testing, and lifecycle complexity.

## G. Updated feasibility matrix

| Capability | Class | Update |
|---|---:|---|
| Android VpnService + foreground lifecycle | A | Directly verified pattern; independently implementable |
| Xray protocols/routing/DNS | B | Xray/AndroidLibXrayLite confirmed |
| sing-box/AnyTLS/TUIC/Snell | B/C | Binding confirmed; licensing/config-version work remains |
| HevTun bridge | B | Confirmed native library |
| Zeptun bridge | B/C | Confirmed; independent suitability/license audit required |
| Platform IKEv2/IPsec | B | Android VpnManager/IKE APIs confirmed |
| OpenConnect | B/C | Native and controller integration confirmed |
| OpenVPN | B/C | sing-openvpn module confirmed |
| Psiphon | C | Core confirmed; licensing and operational complexity remain |
| Tor + obfs4/Snowflake/Conjure | C | Binaries/assets confirmed; lifecycle/testing cost high |
| DNSTT/MasterDNS | C | Components confirmed; protocol/server compatibility work remains |
| Subscriptions/import/QR | A/B | Formats and scheduling confirmed; parser hardening required |
| DNS/routing/per-app split | A/B | Android and engine support confirmed; leak testing required |
| Statistics/latency/throughput/MTU | A/B | Code indicators confirmed; accurate measurement design required |
| Cross-engine chains | C | Models exist; high failure and QA complexity |
| Exact ZedSecure behavior parity | E | Not a project goal and incompatible with independence constraints |

## H. Updated Phase 2 plan

Phase 2 remains blocked until explicit approval and dependency/license decisions.

1. Freeze initial scope: Xray, essential share links, headless VPN, profiles/subscriptions, DNS/routing, per-app split, reconnect, counters, latency.
2. Freeze dependency versions, licenses, distribution obligations, ABIs, and source-offer policy.
3. Build a headless native Kotlin VPN/service state machine with no UI.
4. Integrate one Xray binding and one independently selected TUN bridge.
5. Implement versioned profile/secret/subscription persistence and hostile-input limits.
6. Add DNS/routing/per-app handling and automated leak tests.
7. Add reconnection, counters, latency, throughput, and MTU diagnostics.
8. Add secondary engines only through adapters after core stability.
9. Add platform integrations and UI in their approved later phases.

## I. Final Phase 1 exit assessment

The requested second-pass static audit is complete for the publicly obtainable 3.0.9 ARM64 split artifact. It materially resolves the main architecture, manifest, component, engine, import, and dependency unknowns. Remaining unknowns are primarily runtime/device behavior, secret storage internals, exact Play-byte provenance, other ABIs, and legal/compliance decisions.

**Phase 1 is ready for user review, but is not approved or closed until the user explicitly approves it. No Phase 2 implementation, UI, or ZIP work has started.**
