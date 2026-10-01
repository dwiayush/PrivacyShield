# How to run the compatibility probes without owning any phones

Status: Option A has been run once (2026-09-29, 12 emulators, Android 6-16). Option B has never been run.

**Reading the result page:** the run can say "Success" while one job shows a red cross. That is the Android 17
(API 37) job, which is marked experimental and is allowed to fail. Judge the run by the compat-results table.

## Option A: free emulators for every Android version (start here)

Covers Android 6 to 16 (17 is marked experimental). Shows how each **Android version** behaves, not how
Samsung or Xiaomi customise it.

1. Create a free GitHub account and a new **public** repository (public repos get free CI minutes; private ones have limits).
2. Upload the contents of `PrivacyShield.zip` to it.
3. Open the **Actions** tab, choose **compat-emulators**, click **Run workflow**.
4. Wait for it to finish (expect roughly 10-30 minutes; several jobs run in parallel).
5. Open the finished run and download the **compat-results** file, or read the table in the run summary.
   Send it to me and I will turn the results into the verified `docs/android-limitations.md`.

## Option B: real phones from many brands (Firebase Test Lab)

Covers Samsung, Xiaomi, OPPO group, vivo, Transsion, Honor/Huawei and near-stock phones, as far as
Google's catalog has them. The free Spark plan allows 10 virtual and 5 physical-device test runs per
day (Firebase docs, checked 2026-09-29). Quotas change, so re-check.

One-time setup:

1. Create a Firebase project on the free Spark plan.
2. Enable the **Cloud Testing API** and **Cloud Tool Results API** for the project in Google Cloud.
3. Create a service account with the roles Firebase Test Lab Admin, Storage Object Admin and Service Usage Consumer, and download its JSON key.
4. Create a Cloud Storage bucket for results.
5. In the GitHub repository, add the key as the secret `GCP_SA_KEY` and the bucket name as the variable `FTL_RESULTS_BUCKET`.
6. Actions tab, **compat-firebase-test-lab**, **Run workflow**.

Things I could not verify: whether creating the bucket forces the paid Blaze plan (Blaze has free daily
test minutes, 30 physical and 60 virtual, but **no hard spending cap**, so set a budget alert before
enabling it), and the exact field names in Test Lab's device catalog (`tools/pick_ftl_devices.py` fails
loudly if they differ, instead of testing nothing).

## Option C: paid farms with the widest brand coverage

BrowserStack, AWS Device Farm and LambdaTest advertise larger real-device catalogs than Firebase. They need
an account and payment, and are not scripted yet. Worth adding if Options A and B leave brand gaps.

## What is and is not automated

| Probe | Automated by the app? | Notes |
|---|---|---|
| S1 inventory, S2 install source, S13 manifest, S16 deep links, S19 preinstalled apps, S20 memory, S21 Google/Huawei services | Yes | Answers are recorded per device |
| S3 special-access readable for other apps, S4 accessibility, S5 notification listeners | Yes, partly | Proves the API answers, not that the answer is right. Correctness needs seeded test apps that hold overlay, accessibility, etc. (not built yet) |
| S6 privileged AppOps blocked, S10 usage stats without grant | Yes | Checks the "not available unprivileged" hypotheses |
| S14 old-Android fallbacks | Partly | Emulators for API 23-28 cover inventory and installer, not real Android 6-9 phones |
| S18 profiles | Partly | Counts profiles only; cloned-app spaces need manual installs |
| S7 recording redaction, S8 restricted settings, S9 Private Space, S12 install broadcasts, S17 OEM-only permissions | No | Need a second app or manual UI steps |
| S11 / S15 background scheduling | No | Needs a 24-hour observation on each OEM |

## Privacy of the probes

The probes emit only counts, yes/no answers and store names (for example Play Store), never the names of
other apps or any device identifier. Test Lab and GitHub will still see the model and Android version.
