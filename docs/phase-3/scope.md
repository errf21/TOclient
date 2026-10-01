# Phase 3 — Feature Completion and Compatibility Scope

Phase 3 remains headless. It completes the approved Xray-first functional
surface and compatibility layer without adding the major deferred engines.

## In scope

1. Safe import parsing for VLESS, VMess, Trojan, Shadowsocks, SOCKS, HTTP,
   Hysteria2, and raw Xray JSON.
2. Subscription retrieval, bounded decoding, format detection, normalization,
   duplicate detection, refresh metadata, and atomic profile replacement.
3. Engine-neutral normalized endpoint models rendered into Xray JSON.
4. Domain, IP/CIDR, LAN, DNS, and per-app routing rule completion.
5. DNS policy hardening and leak-safe defaults.
6. Profile validation, deterministic identifiers, migration/version fields,
   and independent backup/export format.
7. Real Xray outbound latency diagnostics and failure classification.
8. Android API 24–36 compatibility gates and foreground/network behavior.
9. ABI/dependency integrity checks, parser fuzz-style tests, and regression tests.

## Explicitly out of scope

- UI/UX
- sing-box adapter
- Tor, Psiphon, OpenConnect, OpenVPN, IKEv2, and DNS-tunnel engines
- cross-engine chains
- final APK/release packaging
- final ZIP
- physical-device results that were not executed

## Exit criteria

- Supported inputs parse into independent normalized models.
- Normalized models render valid bounded Xray configurations.
- Subscription refresh is atomic, deduplicated, and failure-safe.
- DNS/routing rules are validated and rendered without direct fallback by default.
- Profile backup/export is independent and contains no branding-derived format.
- Diagnostics and counters expose typed results.
- API/ABI compatibility checks and the complete non-device test suite pass.
- Deferred real-device QA remains explicitly documented.