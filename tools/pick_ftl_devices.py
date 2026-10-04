#!/usr/bin/env python3
"""Choose Firebase Test Lab devices covering the brand families in docs/compatibility-plan.md.

Input: JSON from `gcloud firebase test android models list --format=json` (path or '-' for stdin).
Output: one `model=ID,version=V` line per chosen device (feed to `gcloud ... --device`).

For each brand family (in priority order) we pick, among PHYSICAL PHONE models that are not
deprecated: the model with the OLDEST supported Android version (at that version) and the model with
the NEWEST (at that version). This gives old and new behavior per brand within a small budget.
The field names are read defensively; if Test Lab changes its schema, the output is empty and the
caller must fail loudly rather than test nothing.
"""
import argparse
import json
import sys

# Brand family -> manufacturer/brand names (lowercase) that belong to it. Order = priority.
FAMILIES = [
    ("samsung", {"samsung"}),
    ("xiaomi", {"xiaomi", "redmi", "poco"}),
    ("oppo", {"oppo", "oneplus", "realme"}),
    ("vivo", {"vivo", "iqoo"}),
    ("transsion", {"tecno", "infinix", "itel", "transsion"}),
    ("honor-huawei", {"honor", "huawei"}),
    ("near-stock", {"google", "motorola", "nothing", "hmd", "nokia"}),
]
BAD_TAGS = {"deprecated", "unsupported", "obsolete"}


def _versions(model):
    out = []
    for v in model.get("supportedVersionIds", []) or []:
        try:
            out.append(int(v))
        except (TypeError, ValueError):
            continue
    return sorted(out)


def eligible(model):
    if str(model.get("form", "")).upper() != "PHYSICAL":
        return False
    ff = str(model.get("formFactor", "PHONE")).upper()
    if ff not in ("PHONE", ""):
        return False
    tags = {str(t).lower() for t in model.get("tags", []) or []}
    if tags & BAD_TAGS:
        return False
    return bool(_versions(model)) and bool(model.get("id"))


def family_of(model):
    names = {str(model.get("manufacturer", "")).lower(), str(model.get("brand", "")).lower()}
    for fam, members in FAMILIES:
        if names & members:
            return fam
    return None


def pick(models, max_devices):
    by_family = {}
    for m in models:
        if eligible(m) and family_of(m):
            by_family.setdefault(family_of(m), []).append(m)
    chosen, seen = [], set()

    def add(model, version):
        key = (model["id"], version)
        if key not in seen and len(chosen) < max_devices:
            seen.add(key)
            chosen.append(key)

    for fam, _ in FAMILIES:
        ms = by_family.get(fam, [])
        if not ms:
            continue
        oldest = min(ms, key=lambda m: (_versions(m)[0], m["id"]))
        newest = max(ms, key=lambda m: (_versions(m)[-1], m["id"]))
        add(oldest, _versions(oldest)[0])
        add(newest, _versions(newest)[-1])
    return chosen


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("models_json", help="path or - for stdin")
    ap.add_argument("--max", type=int, default=4, help="max devices per test run (free Spark quota is limited)")
    args = ap.parse_args(argv)
    if args.models_json == "-":
        raw = sys.stdin.read()
    else:
        with open(args.models_json) as fh:
            raw = fh.read()
    try:
        models = json.loads(raw)
    except ValueError:
        print("Could not parse model list as JSON.", file=sys.stderr)
        return 2
    chosen = pick(models if isinstance(models, list) else [], args.max)
    if not chosen:
        print("No eligible devices found (schema change or empty catalog?).", file=sys.stderr)
        return 2
    for model_id, version in chosen:
        print(f"model={model_id},version={version}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
