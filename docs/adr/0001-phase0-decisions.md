# ADR 0001: Phase 0 decisions

Status: accepted (2026-09-28). Decided by the engineering lead on the owner's delegation
("consider on your own"). Each decision lists what would make us revisit it.

| # | Decision | Reasoning | Revisit when |
|---|---|---|---|
| 1 | **Two distribution flavors:** `play` and `foss` (F-Droid / IzzyOnDroid / direct APK). | `QUERY_ALL_PACKAGES` approval on Google Play is uncertain and an incomplete inventory breaks the core product. The `foss` flavor is also the strongest place to prove the privacy claims. | Play declines the permission declaration: `play` degrades to a limited inventory or is dropped. |
| 2 | **MVP AI is deterministic templates only.** The `offline` build ships **without `INTERNET`**. An LLM `AIProvider` arrives later as a separate `connected` flavor. | "Local-first, and we can prove it" is the most valuable trust property, and it cannot coexist with a network permission in the same binary. Templates also cannot hallucinate. | Usability tests show templates cannot answer real user questions well enough. |
| 3 | **Licensing:** AGPL-3.0 for the app; Apache-2.0 for `rules/`; hosted intelligence is the future paid layer. | Auditability is a product feature. AGPL protects a future hosted layer from being resold as a closed service; Apache-2.0 rules let others reuse the permission catalog. **This is also a business decision, so the owner can overrule it, and it should be settled before the first public commit.** | Before the repository is made public. |
| 4 | **No Advanced (ADB/Shizuku) mode in the MVP.** Possibly a researcher-only mode later. | It is the only route to real per-app sensor history, but it excludes ordinary users and adds risk and complexity. We would rather ship an honest product without the timeline than fake one. | After the MVP, if users ask for authoritative usage history. |
| 5 | ~~minSdk 30~~ **Superseded by ADR 0002** (minSdk 23, tiered support). | API 30 brings `getInstallSourceInfo` and package-visibility rules, so there is little branching below it. Older-Android share in India is a real concern; the core modules do not depend on the API level, so lowering minSdk later is cheap. | Distribution data for the target market says API 30 excludes too many users. |
| 6 | **No backend in the MVP.** `backend/` holds only the API contract. | Nothing in Phase 1 needs one; a backend would add attack surface and undermine the local-first claim. | Phase 3 (threat intelligence, Family/Enterprise). |
| 7 | **Family mode deferred** until a consent-visible design exists. | Any remote-reporting feature is one step from stalkerware. It needs an always-visible indicator and consent from the monitored person before it is built. | A design exists that passes the misuse review in `docs/threat-model.md`. |
| 8 | **`docs/` is canonical.** Only README, SECURITY, CONTRIBUTING and LICENSE live at the repo root. | Removes the duplicated architecture/threat-model files in the original spec. | — |
| 9 | **Test devices are unknown, so no Android behavior is claimed as verified.** Every platform claim is marked UNVERIFIED in `docs/phase0-spike-plan.md` until run on real hardware. | We cannot prove Android behavior by reading documentation. | Devices or emulator images are available. |

## Design consequences worth knowing

- **Overlay and accessibility are one exposure category.** The corroboration gate needs high-weight
  capabilities from two *different* categories before CRITICAL, so overlay + accessibility on their own
  cap at HIGH (with the combination bonus applied). Adding device admin, notification access, SMS or
  background location from another category is what allows CRITICAL. This is deliberate: it is the rule
  that keeps "one kind of access" from ever meaning "malicious".
- **Unknown install source counts as a provenance concern** (with MEDIUM confidence). This will produce
  some false positives on devices where the installer cannot be read, so the `foss` and restricted
  builds must measure it during the spike.
- **All scoring weights are proposals.** They are documented, versioned and testable, but not calibrated.
  Calibration against a golden corpus of popular apps is an MVP exit criterion.
