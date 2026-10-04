# Compatibility plan

Goal (owner requirement): PrivacyShield should work on every Android version in meaningful use and on every
phone brand people use. This document says what that can honestly mean, and how we will prove it device
family by device family instead of claiming it.

## What "all" means here

| Claim we will make | Claim we will NOT make |
|---|---|
| The app installs and runs on Android 6.0 (API 23) and newer, on any brand. | That every feature works identically everywhere. |
| Where a device does not let us observe something, the app says "could not check". | That silence means safe. |
| A public table shows which device families we have **verified**. | That untested devices are supported. |

Out of scope: Android below API 23, and operating systems without Android APIs, such as HarmonyOS NEXT
(IDC reports HarmonyOS shipments nearly tripling to about 51 million units in 2026, so this is a real
audience we cannot serve with an Android app).

## Data snapshot (as of 2026-09-28; refresh before each release)

Version sources disagree because they measure different populations. We use them only to choose the test
matrix, never as a precise claim.

- StatCounter (browser pageviews, April 2026): API 30 and above reaches 86.9% of the measured base and API 33 and above reaches 68.9%.
  Android 16 is the most common single version in StatCounter (June 2026) and AppBrain (September 2026, 25.4%).
- TelemetryDeck (its own app-developer population, end of August 2026) shows API 36 at 54.6% and API 37 at 3.7%,
  much higher than the other sources, which is a reminder that any single source is biased.
- Android 17 (API 37) reached Pixel 6 and later on 16 June 2026 and is not yet a leading version.
- Brand shares below are **new-device shipments**, not devices in use, so the installed base differs.
  IDC Q2 2026: Samsung 22.7%, Apple 20.2%, Xiaomi 11.3%, followed by OPPO and vivo. Counterpoint H1 2026 has
  OPPO at 10.3% and vivo at 7.6%. Transsion (Tecno, Infinix, itel) is a large brand family in emerging
  markets, and OPPO Group includes OnePlus and Realme.

Sources: telemetrydeck.com/survey/android, commandlinux.com/statistics/android-version-distribution,
appbrain.com/stats/top-android-sdk-versions, idc.com/promo/smartphone-market-share, tob.news (Counterpoint),
thenationalnews.com (Counterpoint forecast).

## Android version tiers (provisional, confirmed by the spike)

| Tier | API | Expected observability |
|---|---|---|
| FULL | 33-37 (Android 13-17) | Full signal set, including restricted-settings and newer sideload protections |
| STANDARD | 29-32 (Android 10-12L) | Most signals; some newer ones absent |
| BASIC | 23-28 (Android 6-9) | Inventory, runtime permissions, installer via legacy APIs; fewer special-access signals; results often a lower bound |

Every screen that shows a score on a BASIC or STANDARD device shows the "at least" wording and a
"what we could not check on this phone" list.

## Brand and skin families to verify

Skin names are from general knowledge and are **unverified** until the spike runs. Behavior differences
attributed to a brand below are hypotheses to test, not facts.

| Family | Brands | Why it matters |
|---|---|---|
| Samsung | Galaxy S/A/M/Z | Largest share; Secure Folder; its own battery manager |
| Xiaomi group | Xiaomi, Redmi, POCO | Large share; known for aggressive background limits and OEM-only permissions |
| OPPO group | OPPO, OnePlus, Realme | Large share; own skins and battery managers |
| vivo group | vivo, iQOO | Large share; own skins |
| Transsion | Tecno, Infinix, itel | Major in Africa and South Asia; often low-RAM, Android Go, heavy preinstalled apps |
| Honor / Huawei | Honor; older Huawei | Older Huawei phones lack Google services; new Huawei phones may run HarmonyOS (unsupported) |
| Near-stock | Google Pixel, Motorola, Nothing, HMD | Closest to AOSP; the reference behavior |
| Long tail | Sony, Asus, Lenovo, Nokia, rugged and kids' phones, TV/tablet builds | Handled by graceful degradation, not individual testing |

## How we test without owning every phone

Step-by-step instructions: `docs/how-to-run-device-farms.md`.

1. **AOSP emulators, API 23 to 37.** Cheap, automated, in CI. They prove API-level behavior but say
   **nothing** about OEM behavior.
2. **Cloud device farms** (for example Firebase Test Lab, AWS Device Farm, BrowserStack, and Samsung's remote
   test service, subject to their current availability). Real OEM hardware for the spike probes S1-S21.
3. **A small physical lab**: one device per family above, plus one Android Go device under 2 GB RAM, one
   Android 6-8 device, and one Huawei phone without Google services.
4. **Opt-in compatibility reports from users, with no telemetry.** A user can tap "Create compatibility
   report": a small file listing model, Android version, patch level, and which probes succeeded. It contains
   **no app inventory**. The user previews it and sends it themselves (the offline build cannot send
   anything). It goes in the outbound-disclosure log. This is how the long tail gets covered honestly.
5. **Unknown devices degrade, they do not fail.** Every collector has a fallback to `unobservable`. A
   phone from a brand we have never seen still works; it just reports more "could not check".

## Public compatibility table

Published in the README and kept current. Each device family has one of:

- **Verified**: spike probes passed on real hardware (device, Android version and date listed).
- **Expected**: works on emulators of that API level; OEM behavior not yet checked.
- **Unverified**: no evidence yet.

## Exit criteria for "broad compatibility"

- Zero crashes across the emulator matrix API 23-37 in CI.
- Spike probes run on real hardware for every family in the table above.
- Every "could not check" state has a test proving it adds no points and is disclosed.
- Scan completes within the memory and time budget on the low-RAM device.
- A device with no Google services runs the full offline flavor.
