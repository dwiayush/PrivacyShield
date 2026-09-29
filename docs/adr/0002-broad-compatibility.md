# ADR 0002: Broad device and Android-version compatibility

Status: accepted (2026-09-28). Supersedes decision 5 of ADR 0001.

## Context

The owner requires PrivacyShield to work on every Android version in current use and on every phone brand
people use worldwide. ADR 0001 chose minSdk 30, which by StatCounter's April 2026 figures reaches about
87% of measured devices and leaves out the remaining ~13%, disproportionately older and lower-cost phones
in the markets this product cares about.

"Every version and every brand" cannot be met literally. Some phones run operating systems with no Android
APIs at all (for example HarmonyOS NEXT), and some Android versions cannot expose the signals we want.

## Decision

1. **minSdk 23 (Android 6.0).** Below it, permissions are granted at install time, so the permission model
   we explain to users does not apply. Compile and target SDK are the latest stable (Android 17 is out).
2. **Tiered support, disclosed in the UI.** `SupportTier` (FULL 33+, STANDARD 29-32, BASIC 23-28) determines
   which signals are attempted. Boundaries are provisional until the spike confirms them.
3. **"Could not check" is a first-class state.** Anything we cannot observe on a given device is recorded
   as `unobservable`, adds **zero** points (we never guess), makes the result a lower bound ("at least"),
   and is disclosed in alerts and the device health screen. Silence never means safe.
4. **OEM behavior is tested, not assumed.** The spike gains probes S14-S21 covering old-API fallbacks, OEM
   background killing, settings deep links, OEM-specific permissions, cloned-app spaces, preinstalled
   apps, low-RAM devices, and devices without Google services.
5. **Distribution follows the devices.** Devices without Google Play services (Huawei EMUI/HMS, de-Googled
   builds) are served by the `foss` flavor and OEM stores, not Play alone.
6. **Explicitly out of scope:** HarmonyOS NEXT and other non-Android systems; Android below API 23.

## Consequences

- More code paths and a wider test matrix. The pure-Kotlin core is unaffected by API level, which is why
  it is kept free of Android dependencies.
- Some Android 6-9 features are unavailable, so a scan there will often be a lower bound.
- The "system app = expected" score halving may under-warn on OEM-preinstalled apps that users cannot
  remove. Probe S19 decides whether it stays, becomes role-only, or becomes per-OEM. Until then it is an
  acknowledged calibration risk.
- Library minimums (Compose, Room, WorkManager, SQLCipher) must be checked against API 23 when the Gradle
  build is created. If a required library raises its minimum, pin an older release or record a new ADR.
