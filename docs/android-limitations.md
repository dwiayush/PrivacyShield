# Android limitations: what we have actually observed

Evidence from **one run on 2026-09-29** of `compat-emulators` (GitHub Actions, run #1, Google AOSP-based
emulator images, `google_apis`, x86_64) on Android 6.0, 7.0, 8.0 and 9 to 16 (API 23, 24, 26, 28-36).
Each version was run once. **Nothing here is about Samsung, Xiaomi, OPPO, vivo, Transsion or any other
manufacturer's builds**, and nothing was tested on a physical phone. Android 17 (API 37) failed to start
in CI for an unexplained reason and is untested.

Status words: **OBSERVED** (seen in this run), **INCONCLUSIVE** (the run cannot tell), **UNTESTED**.
Anything not listed here is UNVERIFIED and must not appear in user-facing text.

## Observed

| # | Finding | Evidence | Design consequence |
|---|---|---|---|
| 1 | The probe app builds with AGP 8.7.3, Gradle 8.10.2, Kotlin 2.0.21, compileSdk 35, **minSdk 23**, and runs on API 23-36. | 12 of 12 non-experimental jobs built and produced probe output | The toolchain and minSdk 23 are viable for a simple app. Compose, Room, WorkManager and SQLCipher at API 23 are still unchecked. |
| 2 | The full package inventory including granted-permission flags works with `QUERY_ALL_PACKAGES`. | S1 passed on all 12; 8-95 ms with 73-248 system apps | Core scan is feasible. Only tiny inventories were tested. |
| 3 | Install source is readable on every version by different APIs: `getInstallerPackageName` (API 23-29 seen) and `getInstallSourceInfo` (API 30-36). | S2 passed on all 12; adb-installed apps report a null installer | The provenance signal works, but only "installed via adb = null" has been seen. Play, other stores and file-manager installs are untested. |
| 4 | The **all-files access** state cannot be read below API 30, and its settings screen does not exist below API 30. | S3 and S16 on API 23-29 | It does not exist there: mark **not applicable**, not "could not check" (`Capability.isApplicableOn`). |
| 5 | The **install-unknown-apps** settings screen is absent on API 23-24 but present on 26, while its state read failed on 23, 24 **and 26**. | S16 vs S3 | Not applicable below 26. The 26 failure is unexplained; the raw logs are needed to tell an unrecognised op name from a real limit. |
| 6 | Overlay, usage-access and install-unknown states answered without error on API 30-36 (28-29 for overlay and usage access). | S3 on API 28-36 | Promising, but only 2 user apps existed, both ours. Values were **not** checked for correctness (probe S3S added). |
| 7 | Settings deep links for accessibility, notification listeners, overlay, usage access, security and app details all resolved on every version. | S16 on all 12 | The "Review Settings" buttons are feasible on stock Android. OEM screens are untested. |
| 8 | Historical per-app AppOps data was not available: on API 23-28 the method is absent (hidden API), on 29-30 it throws `SecurityException`. | S6 | Per-app mic/camera/location timelines remain out of scope for the unprivileged product. |
| 9 | The spike's merged manifest contained only `QUERY_ALL_PACKAGES`. | S13 on all 12 | Says nothing about the future product manifest, which needs its own CI check. |
| 10 | On clean Google images, about 23-34 system apps already hold granted sensitive permissions (camera, mic, SMS, contacts, call log, location). | S19: 26/109 (API 28) up to 33-34/241-248 (API 35-36) | An AOSP baseline for the OEM comparison. |
| 11 | Every image had exactly 1 user profile, was not low-RAM (about 1 GB on API 23-24, 1.5-2.5 GB later) and had Google Play services. | S18, S20, S21 | Emulators cannot answer the low-RAM, cloned-space or no-Google-services questions. |

## Inconclusive: the run cannot tell

| Item | What happened | Why it proves nothing | Follow-up |
|---|---|---|---|
| Per-app AppOps history on **API 31-36** | The call returned 0 entries and no exception | A fresh emulator may simply have no recorded usage | Probe **S6C** creates a known entry, then asks again |
| Usage stats without a grant | Nothing returned without the grant on all 12 | No comparison with a granted state | Probe **S10G** compares before and after granting |
| Notification listeners on API 23-24 | Reported a failure | Probe flaw: the setting was simply unset, not unreadable | Fixed with a control key; **not re-run** |

## Corrections to our own earlier assumptions

- **"Unobservable" and "not applicable" are different.** The design used only "could not check". Real
  data shows some accesses cannot exist on older Android. Counting those as blind spots would wrongly tell
  a user on Android 9 that their phone is less checked than it is.
- **An empty answer is not a pass.** The first aggregator marked "0 entries" on API 31-36 as PASS. It now
  reports INCONCLUSIVE, with a regression test.
- **A green workflow run is not a green result.** The run shows "Success" even though one job (API 37, marked experimental) failed.

## Still untested, in priority order

1. Real phones by brand (Firebase Test Lab): S1-S5 and S19 on OEM builds.
2. Correctness of special-access values (S3S), the two controls (S6C, S10G), and S5 after its fix.
3. Installs from Play and other routes; large inventories (binder limit); inventory timing on real phones.
4. Android 17 (API 37) and newer.
5. Restricted settings, Private Space, cloned-app spaces, background scheduling per brand (manual probes).
