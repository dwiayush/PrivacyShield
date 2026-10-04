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

