# PrivacyShield

An Android privacy-intelligence app that helps ordinary people understand **what their apps can do with
their data, permissions, sensors and device**, with evidence and honest uncertainty. It is not an antivirus.

## Status: pre-alpha, Phase 0

| Area | State |
|---|---|
| Risk engine, alert policy, device health score (pure Kotlin) | Implemented; **31 tests pass** on the JVM, including an exhaustive invariant check |
| Verification probe app (`android/spike`) and device-farm workflows | Written; probe code **type-checked** against the Android 35 API but **never run**; workflow YAML validated but **never executed** |
| Probe log aggregator and Firebase device picker (Python) | Implemented; **14 tests pass** |
| Android app, collectors, database, UI | **Not started** |
| Android API behavior | **Unverified**; see `docs/phase0-spike-plan.md` |
| Gradle build | **Not created** (no access to Maven in the authoring environment) |

Nothing here has been run on an Android device yet.

## Compatibility goal

Support every Android version still in meaningful use (API 23 and up) on every major phone brand, with
honest tiers: where an Android version or brand does not let us observe something, the app says so
instead of guessing. See `docs/compatibility-plan.md`.

## Principles

Exposure is not intent: a permission is never evidence of malicious behavior. Every alert shows evidence,
confidence, possible innocent causes, and what we cannot know. We never auto-disable or uninstall anything.
The offline build ships without the `INTERNET` permission. Data is minimized and stays on the device.

## Layout

```
android/core/model   Domain types (pure Kotlin, no Android dependency)
android/core/risk    Rule catalog, risk engine, alert policy, device health
docs/                adr/ (decisions), compatibility-plan.md, risk-model.md (generated), phase0-spike-plan.md
rules/               Versioned rule data (Apache-2.0)
android/spike       Throwaway probe app run on device farms (Phase 0)
tools/               check.sh, probe aggregator, Firebase device picker
tests/               Tests for the Python tools
.github/workflows/   core-tests, compat-emulators, compat-firebase
```
More modules (data, security, features, backend) are added when there is code to put in them.

## Run the tests

Requires a JDK and a standalone `kotlinc` (Gradle build to follow):

```sh
KOTLINC=/path/to/kotlinc ./tools/check.sh   # Kotlin core
python3 -m unittest discover -s tests       # Python tools
```

To run the device probes without owning phones, see `docs/how-to-run-device-farms.md`.

## License

App: AGPL-3.0 (see `LICENSE`). `rules/`: Apache-2.0 (see `rules/LICENSE`). See `docs/adr/0001-phase0-decisions.md`.
