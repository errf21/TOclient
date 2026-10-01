# Phase 2 Exit Criteria

- The headless app source compiles for API 24+ and target API 36.
- VPN permission and foreground-service lifecycle are correctly represented.
- A valid persisted Xray profile can be decrypted, normalized, started, and stopped.
- A real Android TUN descriptor is connected to Xray's local SOCKS inbound through HevTun.
- Full-tunnel, DNS, IPv4/optional IPv6, and per-app policies are applied by `VpnService.Builder`.
- Network changes trigger bounded reconnection without duplicate engines or leaked descriptors.
- Engine and TUN traffic counters report real native-core values.
- Invalid configuration and partial startup failures clean up in reverse order.
- Core state-machine, policy, parsing, and failure tests pass.
- Headless real-device tests cover traffic, DNS leaks, reconnect, sleep/wake, and shutdown.
- No UI/UX implementation has started.