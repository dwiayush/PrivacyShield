# Risk model

> **Generated file.** The rule tables below are produced from `RuleCatalog` in
> `android/core/risk` by `tools/check.sh`. Edit the code, not this file. This header lives in
> `risk-model.header.md`.

## What the score means

The score is an **exposure score**: how much sensitive access an app holds, adjusted for where it came
from and whether it recently changed. It is **not** a probability that the app is malicious, and
PrivacyShield does not detect malware. Weights are **proposals awaiting calibration** against a golden
corpus of real apps; they are not empirical findings.

## Guarantees enforced by tests

These hold for all 65,536 capability subsets across six contexts (see `RiskEngineTests.kt`):

- A score is always 0-100 and its signals sum exactly to the raw score (the arithmetic is always shown).
- No single signal is ever CRITICAL.
- CRITICAL requires high-weight capabilities from at least two independent categories **and** corroboration.
- A single capability can never produce CRITICAL.
- Results are deterministic and independent of input ordering.
- Marking an app "trusted" suppresses its alert but never changes its score or facts.
- Generated text never uses certainty or alarm words (malware, spying, and similar) and never embeds
  the app's self-chosen label.

Ruleset version: `2026.09.0`

### Capability points (E)

| Capability | Points | Category | High-weight (>=10) |
|---|---|---|---|
| `ACCESSIBILITY_CONTENT` | 25 | SCREEN_CONTROL | yes |
| `NOTIFICATION_LISTENER` | 15 | COMMUNICATIONS | yes |
| `SMS` | 12 | COMMUNICATIONS | yes |
| `DEVICE_ADMIN` | 12 | DEVICE_CONTROL | yes |
| `ACCESSIBILITY_BASIC` | 10 | SCREEN_CONTROL | yes |
| `OVERLAY` | 10 | SCREEN_CONTROL | yes |
| `BACKGROUND_LOCATION` | 10 | SENSORS | yes |
| `CALL_LOG` | 8 | COMMUNICATIONS | no |
| `INSTALL_UNKNOWN_APPS` | 8 | DEVICE_CONTROL | no |
| `ALL_FILES` | 8 | DATA_ACCESS | no |
| `CAMERA` | 5 | SENSORS | no |
| `MICROPHONE` | 5 | SENSORS | no |
| `USAGE_ACCESS` | 5 | DATA_ACCESS | no |
| `CONTACTS` | 4 | COMMUNICATIONS | no |
| `FINE_LOCATION` | 4 | SENSORS | no |
| `COARSE_LOCATION` | 2 | SENSORS | no |

Points are multiplied by 0.5 for system apps and for capabilities the app plausibly
holds through a default role (for example the default SMS or dialer app).
If an app has `ACCESSIBILITY_CONTENT`, `ACCESSIBILITY_BASIC` is not counted separately.

### Provenance (P)

+8.0 if the app is not a system app, did not come from a trusted store
(sideloaded or unknown source), and holds at least one high-weight capability.

### Documented combinations (C)

| Rule | Points | Needs non-store source | Description |
|---|---|---|---|
| `COMBO_OVERLAY_ACCESSIBILITY` | 15.0 | no | Overlay permission together with an accessibility service |
| `COMBO_LISTENER_SMS_NON_STORE` | 12.0 | yes | Notification access together with SMS access, from a non-store source |
| `COMBO_ACCESSIBILITY_ADMIN` | 12.0 | no | An accessibility service together with device administrator status |

### Recent change (R)

| Change | Points at age 0 |
|---|---|
| `NEW_HIGH_WEIGHT_GRANT` | 10.0 |
| `SIGNING_CERT_CHANGED` | 15.0 |
| `UPDATE_ADDED_SENSITIVE_PERMISSIONS` | 8.0 |

Points decay linearly to 0 over 14 days. Each kind counts once (most recent).

### Levels and the corroboration gate

`score = min(100, round(E + P + C + R))`

LOW < 15, MEDIUM 15-34, HIGH 35-59, CRITICAL >= 60.

**Gate:** an app reaches CRITICAL only if (a) its high-weight capabilities span at least
2 different exposure categories, AND (b) at least one of: a provenance
concern, a recent change, or a documented combination. Otherwise the level is capped at HIGH
and the result is flagged `cappedByGate`. A single kind of access can never produce CRITICAL.
