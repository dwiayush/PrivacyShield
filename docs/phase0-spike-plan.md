# Phase 0 verification spike

> **Running it:** see `docs/how-to-run-device-farms.md`. Probes S1-S6, S10, S13, S16, S18-S21 are implemented in
> `android/spike/` (type-checked against Android 35, never run). The rest need manual steps.

Purpose: replace every "I believe Android does X" in our design with "we ran it on real devices and saw Y".
Until a row below says PASS or FAIL with a device and Android version, the claim is **UNVERIFIED** and
must not appear in user-facing text, README, or Play listing copy.

**How to run:** build a throwaway app (`spike/`, not the product) that runs each probe and writes JSON
results. Run on at least: Android 12, 13, 14, 15, and the latest stable, across at least two OEMs, including
one low-end device. Record model, OEM skin, Android version, and security patch for every result.

| ID | Hypothesis (from feasibility matrix) | Probe | Pass criterion | Status |
|---|---|---|---|---|
| S1 | With `QUERY_ALL_PACKAGES`, `getInstalledPackages` returns all user apps on API 30+; without it, it does not. | Count packages with and without the permission | Counts differ as predicted; the inventory works with the permission | NOT RUN |
| S2 | `getInstallSourceInfo` distinguishes Play, other store, ADB and file-manager installs. | Install the same APK four ways | Installer values differ per route; document the actual values per OEM store | NOT RUN |
| S3 | `AppOpsManager.unsafeCheckOpNoThrow` reads OVERLAY, USAGE_ACCESS, INSTALL_UNKNOWN, ALL_FILES state for **other** packages without special privileges. | Query each op for a known holder | Correct state on each version; record where it throws or returns garbage | NOT RUN |
| S4 | `AccessibilityManager` lists enabled services with capability flags; `isAccessibilityTool` is present on API 34+. | Enable a test service | Service and capability flags reported correctly | NOT RUN |
| S5 | `enabled_notification_listeners` is readable and names listener components. | Enable a test listener | Correct component names; note OEM variations | NOT RUN |
| S6 | Per-app historical mic/camera/location usage is **not** available unprivileged (`GET_APP_OPS_STATS`). | Call `getPackagesForOps` without the grant | SecurityException or empty; confirms these timelines stay out of the product | NOT RUN |
| S7 | `AudioRecordingCallback` reports recording activity but redacts client identity for non-privileged apps. | Record from a second app while the spike observes | Activity seen; client uid/package redacted | NOT RUN |
| S8 | Restricted-settings behavior (API 33+) for sideloaded apps affects enabling accessibility and notification access, and can be detected. | Sideload a test app and try to enable each | Behavior documented; decide whether "restricted" is a usable signal | NOT RUN |
| S9 | Private Space apps (API 35+) are invisible to the inventory. | Install an app in Private Space | Confirm the gap; write the user-facing disclosure | NOT RUN |
| S10 | `UsageStatsManager` events give foreground/background correlation, with retention of only days. | Grant usage access and query | Retention window measured per device | NOT RUN |
| S11 | WorkManager periodic scans run at the requested cadence on battery-managed OEMs. | Schedule a 15-minute job, observe for 24h | Measure actual intervals; define "stale scan" thresholds | NOT RUN |
| S12 | Package broadcasts (install/update/remove) arrive reliably while the app is alive and are recovered by diff after being missed. | Install/uninstall with the app alive, killed, and swiped away | Diff scan always reconciles | NOT RUN |
| S13 | The merged manifest contains only the permissions we intend. | `aapt dump permissions` on a release build | Matches the allowlist in `docs/privacy-model.md` | NOT RUN |

### Compatibility probes (added by ADR 0002)

Run these across the OEM matrix in `docs/compatibility-plan.md`, not just on Pixel-like devices.

| ID | Hypothesis | Probe | Pass criterion | Status |
|---|---|---|---|---|
| S14 | On API 23-28 the inventory, runtime-permission state and installer are readable through legacy APIs (`getInstallerPackageName`). | Run S1-S2 on Android 6-9 devices/emulators | Inventory and provenance work; list which special-access signals are unobservable | NOT RUN |
| S15 | OEM skins (Xiaomi, OPPO, vivo, Transsion, Samsung, Honor, Huawei) kill or delay WorkManager jobs; a stale scan is detectable. | Run S11 per OEM with default battery settings | Measure real intervals per OEM; define per-OEM guidance and "stale scan" thresholds | NOT RUN |
| S16 | OEM settings screens for accessibility, notification access, overlay and app details are reachable by our deep-link intents. | Fire each intent per OEM | Every intent resolves, or a fallback path is documented | NOT RUN |
| S17 | OEM-specific permissions (for example autostart, pop-ups while in background) are not visible through standard APIs. | Compare OEM permission screens with what we read | Gap list per OEM; these are shown as UNAVAILABLE, never guessed | NOT RUN |
| S18 | Cloned apps and second spaces (dual apps, Second Space, Secure Folder, work profiles, guest users) are missing from the inventory. | Install apps into each | Document each blind spot and how the UI discloses it | NOT RUN |
| S19 | Preinstalled OEM apps hold sensitive access commonly enough that the "system app = expected" halving hides real exposure. | Inventory a fresh device per OEM | Decide whether halving stays, becomes role-only, or becomes per-OEM | NOT RUN |
| S20 | Android Go / low-RAM devices can run a full scan within a memory and time budget. | Scan on a 1-2 GB device | Budget met, or the scan is chunked | NOT RUN |
| S21 | On devices without Google Play services, the app works fully. | Run on a Huawei (HMS) and a de-Googled device | Core features work with no GMS dependency | NOT RUN |

## Non-device checks

- **Play policy pre-check:** read the current Play policies for `QUERY_ALL_PACKAGES`, `PACKAGE_USAGE_STATS`, `VpnService`,
  and security-app claims, and record the wording and date. Policies change; do this immediately before submission too.
- **Newer Android (16+):** review current release notes for restrictions touching accessibility, notification
  listeners and sideloading. Our knowledge of these versions is low-confidence.

## Output

A verified `docs/android-limitations.md` in which every row cites the probe ID and the devices it was
observed on. Anything that fails its hypothesis changes the design before implementation, not after.
