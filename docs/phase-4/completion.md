# Phase 4 Completion — Native UI/UX

Date: 2026-09-28

## Visual direction

The supplied Orb Interaction v3 prototype was used as the primary visual
reference, not as source code or a template. The native implementation carries
forward its calm network-client atmosphere, signal teal, cool paper light
theme, deep green-black dark theme, restrained glass surfaces, rounded-stroke
icon language, portrait-first rhythm, and central connection orb.

The implementation deliberately improves the reference by:

- raising native body-copy readability and keeping touch targets at 48dp;
- separating selected, active, loading, reconnecting, failed, and unavailable
  states truthfully;
- exposing only real traffic and diagnostics values;
- adding complete UI for the real core features absent from the prototype;
- preserving technical values in LTR while supporting Persian RTL layout;
- making motion optional when Android animations are disabled.

## Native implementation

- Added a native Kotlin Android activity and reusable visual components without
  embedding or shipping the HTML prototype.
- Added animated connection orb states for disconnected, connecting, connected,
  reconnecting, and failed states.
- Added responsive Home, Profiles, Profile detail, Diagnostics, Settings, DNS,
  Routing, Tunnel/app policy, Subscriptions, Backup, and About destinations.
- Added dark/light themes and English/Persian direction switching with
  persistent preferences.
- Added empty, loading, validation, error, success, and unavailable states.
- Added an original adaptive/legacy/monochrome TO Client app icon derived from
  an abstract “21” network mark.

## Real core integration

- VPN consent, service startup, switching, cancellation, disconnect, reconnect,
  and failure states are wired to the existing foreground VPN service.
- Live engine/TUN counters and Xray latency measurements are exposed by a
  process-local read-only service bridge; the service remains the sole owner of
  engine and TUN lifecycle.
- Profiles use real encrypted persistence, import parsing, fingerprinting,
  selection, rename, copy, deletion, and configuration metadata.
- Text/file imports accept the complete supported parser surface.
- HTTPS subscriptions support encrypted URLs, refresh, metadata, atomic
  replacement, deletion, and periodic scheduling.
- DNS, domain/CIDR routing, LAN bypass, excluded TUN routes, MTU, IPv6,
  reconnect limits, and per-app package policy edit the real profile model.
- Password-protected backup and restore use the independent encrypted
  `TOCLIENT-BACKUP` codec.
- Android API/ABI, notification, and VPN-consent compatibility states are
  presented without fabricated results.

## Validation

- Core JVM tests: **34 passed, 0 failed**.
- Android JVM tests: **1 passed, 0 failed**.
- Android instrumentation sources: compiled successfully.
- Native UI Kotlin/resources and merged manifest: compiled successfully.
- Android lint: passed with only the intentional API-36/tool-version update
  notices.
- HevTun: built successfully for `arm64-v8a`, `armeabi-v7a`, `x86`, and
  `x86_64`.
- The original icon was rendered and visually inspected at source scale.

## Deferred to Phase 5

- Physical-device execution, native screen-by-screen visual inspection,
  instrumentation execution, VPN consent interaction, live tunnel traffic,
  QR scanning (not an existing core capability), release packaging, APK
  generation, and the final source ZIP.

No Phase 5 work, APK packaging, or final ZIP generation was performed.