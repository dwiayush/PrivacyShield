#!/usr/bin/env python3
"""Turn PS_PROBE logcat output from device farms into a compatibility table.

Usage: aggregate_probes.py PATH [PATH ...] [--md OUT.md] [--json OUT.json]
PATH is a logcat file or a directory searched recursively (Firebase Test Lab, emulator runs, ...).

A "PASS" means the API answered in the way our design needs. It does NOT prove the answer was
correct: correctness of special-access values needs seeded test apps (see docs/phase0-spike-plan.md).
"""
import argparse
import json
import os
import sys

ALLOWED_PERMISSION_SUFFIXES = (".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",)
ALLOWED_PERMISSIONS = {"android.permission.QUERY_ALL_PACKAGES"}
PROBE_ORDER = ["S1", "S2", "S3", "S3S", "S4", "S5", "S6", "S6C", "S10", "S10G", "S13", "S16", "S18", "S19", "S20", "S21"]


def parse_text(text):
    """Extract probe records from raw logcat text. Tolerates threadtime/brief formats and garbage."""
    records = []
    for line in text.splitlines():
        idx = line.find("PS_PROBE")
        if idx < 0:
            continue
        j = line.find("{", idx)
        if j < 0:
            continue
        try:
            rec = json.loads(line[j:])
        except ValueError:
            continue  # truncated or corrupted line: ignore rather than guess
        if isinstance(rec, dict) and {"run", "probe", "data"} <= rec.keys():
            records.append(rec)
    return records


def scan_paths(paths):
    """Return (records, empty_files). An empty file means a job ran but no probe output was captured."""
    records, empty_files = [], []
    for path in paths:
        files = []
        if os.path.isdir(path):
            for root, _, names in os.walk(path):
                files += [os.path.join(root, n) for n in sorted(names)]
        else:
            files.append(path)
        for f in files:
            try:
                with open(f, "r", errors="replace") as fh:
                    found = parse_text(fh.read())
            except OSError:
                continue
            records += found
            if not found:
                empty_files.append(os.path.basename(f))
    return records, empty_files


def read_paths(paths):
    return scan_paths(paths)[0]


def group_runs(records):
    runs = {}
    for r in records:
        run = runs.setdefault(r["run"], {"run": r["run"], "probes": {}})
        run["probes"][r["probe"]] = r["data"]
    for run in runs.values():
        run["device"] = run["probes"].pop("DEVICE", {})
    return sorted(runs.values(), key=lambda r: (r["device"].get("manufacturer", ""), r["device"].get("model", ""), r["device"].get("sdkInt", 0), r["run"]))


def _err(d):
    return "error" in d


def verdict(probe, d):
    """Return (status, summary). Status: PASS, FAIL, INCONCLUSIVE, OBSERVED, ERROR.

    INCONCLUSIVE means the probe got no data but could not prove the data would exist if access were
    allowed (for example an empty list on a fresh device). Control probes (S3S, S6C, S10G) settle these.
    """
    if probe == "S1":
        if _err(d):
            return "ERROR", d["error"]
        s = f'{d.get("userCount", "?")} user / {d.get("systemCount", "?")} system, {d.get("msWithPermissions", "?")} ms'
        if "permsError" in d:
            return "FAIL", f'GET_PERMISSIONS failed: {d["permsError"]}; ' + s
        ok = d.get("countWithPermissions", 0) > 0 and d.get("holdsQueryAllPackages")
        return ("PASS" if ok else "FAIL"), s
    if probe == "S2":
        if _err(d):
            return "ERROR", d["error"]
        top = next(iter(d.get("installers", {})), "none")
        return ("PASS" if d.get("errors", 0) == 0 else "FAIL"), f'{d.get("api")}; top installer: {top}; errors: {d.get("errors")}'
    if probe == "S3":
        if _err(d):
            return "ERROR", d["error"]
        bad = []
        for op, groups in d.items():
            user = groups.get("user", {}) if isinstance(groups, dict) else {}
            keys = [k for k in user if k != "_other"]
            if user and all(k.startswith("exc:") for k in keys):
                bad.append(f'{op} ({keys[0][4:]})')
        return ("FAIL" if bad else "PASS"), ("unreadable: " + ", ".join(bad)) if bad else "all four ops answered"
    if probe == "S3S":
        if _err(d):
            return "ERROR", d["error"]
        expected = {"allow": 0, "ignore": 1, "deny": 2}
        problems = []
        for op, modes in d.items():
            if not isinstance(modes, dict):
                continue
            if "error" in modes:
                problems.append(f'{op}: {modes["error"]}')
                continue
            wrong = [m for m, want in expected.items() if modes.get(m) != want]
            if wrong:
                problems.append(f'{op}: {", ".join(f"{m}={modes.get(m)}" for m in wrong)}')
        return ("FAIL" if problems else "PASS"), ("; ".join(problems) if problems else "value read equals value set for all four ops")
    if probe == "S4":
        if _err(d):
            return "ERROR", d["error"]
        return "PASS", f'{d.get("enabledCount")} enabled; {d.get("enabledCanReadWindowContent")} can read content'
    if probe == "S5":
        if _err(d):
            return "ERROR", d["error"]
        readable = d.get("controlReadable", d.get("readable"))  # older logs used "readable"
        note = "" if d.get("settingSet", True) else " (setting unset: none enabled)"
        return ("PASS" if readable else "FAIL"), f'{d.get("enabledCount")} listeners{note}; secure settings readable={readable}'
    if probe == "S6":
        # Hypothesis: NOT available without privileged access. An answer with entries means the hypothesis is wrong.
        if _err(d):
            return "PASS", f'blocked as predicted ({d["error"].rsplit(".", 1)[-1]})'
        entries = d.get("returnedEntries", 0)
        if entries and entries > 0:
            return "FAIL", f"returned {entries} entries"
        return "INCONCLUSIVE", "returned 0 entries; an empty list could also mean no usage yet (see S6C)"
    if probe == "S6C":
        if _err(d):
            return "PASS", f'blocked as predicted ({d["error"].rsplit(".", 1)[-1]})'
        others = d.get("otherPackagesVisible", 0)
        if others:
            return "FAIL", f"{others} OTHER packages visible without privileges"
        if d.get("returnedEntries", 0) == 0:
            return "PASS", "empty even though an entry exists: filtered or blocked"
        return "PASS", "only our own package visible"
    if probe == "S10G":
        if _err(d):
            return "ERROR", d["error"]
        wo, wi = d.get("without", {}), d.get("with", {})
        wo_n, wi_n = wo.get("events", 0) + wo.get("dailyStats", 0), wi.get("events", 0) + wi.get("dailyStats", 0)
        if wo_n > 0:
            return "FAIL", f"data available without a grant ({wo_n})"
        if wi_n == 0:
            return "INCONCLUSIVE", "no data even after granting: nothing to compare"
        return "PASS", f"nothing without a grant, {wi_n} items with it"
    if probe == "S10":
        if _err(d):
            return "ERROR", d["error"]
        avail = d.get("eventsAvailableWithoutGrant")
        return ("FAIL" if avail else "OBSERVED"), f"events without grant: {avail} (see S10G for the comparison)"
    if probe == "S13":
        if _err(d):
            return "ERROR", d["error"]
        req = d.get("requested", [])
        extra = [p for p in req if p not in ALLOWED_PERMISSIONS and not p.endswith(ALLOWED_PERMISSION_SUFFIXES)]
        return ("FAIL" if extra else "PASS"), ("unexpected: " + ", ".join(extra)) if extra else f"{len(req)} permission(s), all allowed"
    if probe == "S16":
        if _err(d):
            return "ERROR", d["error"]
        missing = [k for k, v in d.items() if v is False]
        return ("FAIL" if missing else "PASS"), ("no handler for: " + ", ".join(missing)) if missing else "all settings screens resolve"
    if probe == "S18":
        return ("ERROR", d["error"]) if _err(d) else ("OBSERVED", f'{d.get("userProfilesVisible")} profile(s)')
    if probe == "S19":
        if _err(d):
            return "ERROR", d["error"]
        s, u = d.get("system", {}), d.get("user", {})
        return "OBSERVED", f'system apps holding sensitive perms: {s.get("holdingAnyGranted")}/{s.get("apps")}; user: {u.get("holdingAnyGranted")}/{u.get("apps")}'
    if probe == "S20":
        return ("ERROR", d["error"]) if _err(d) else ("OBSERVED", f'lowRam={d.get("isLowRamDevice")}, {d.get("totalMemMb")} MB')
    if probe == "S21":
        if _err(d):
            return "ERROR", d["error"]
        return "OBSERVED", f'GMS={d.get("googlePlayServices")}, HMS={d.get("huaweiMobileServices")}'
    return "OBSERVED", ""


def evaluate(runs):
    out = []
    for run in runs:
        dev = run["device"]
        results = {p: verdict(p, run["probes"][p]) for p in PROBE_ORDER if p in run["probes"]}
        missing = [p for p in PROBE_ORDER if p not in run["probes"]]
        out.append({"run": run["run"], "device": dev, "results": results, "missing": missing})
    return out


def to_markdown(evaluated, empty_files=()):
    icons = {"PASS": "PASS", "FAIL": "FAIL", "INCONCLUSIVE": "inconcl", "OBSERVED": "obs", "ERROR": "ERR"}
    lines = [
        "# Device compatibility results",
        "",
        "> Generated by `tools/aggregate_probes.py`. PASS = the API answered the way our design needs; it does",
        "> **not** prove the answer was correct. FAIL = the hypothesis in `docs/phase0-spike-plan.md` did not hold",
        "> on that device. ERR = the probe threw. inconcl = no data, and we cannot tell whether that is because access is blocked. obs = observation only. Emulator rows show AOSP behavior, not OEM behavior.",
        "",
        f"Runs: {len(evaluated)}",
        "",
    ] + ([
        f"> **WARNING: no probe data captured in: {', '.join(sorted(empty_files))}.** A job ran but its log held no probe output "
        "(for example logcat lines dropped under load). Treat those Android versions as **UNTESTED**, not as passing.",
        "",
    ] if empty_files else []) + [
        "| Device | Android (API) | Emulator | " + " | ".join(PROBE_ORDER) + " |",
        "|---|---|---|" + "|".join(["---"] * len(PROBE_ORDER)) + "|",
    ]
    for e in evaluated:
        d = e["device"]
        name = f'{d.get("manufacturer", "?")} {d.get("model", "?")}'
        cells = [icons[e["results"][p][0]] if p in e["results"] else "missing" for p in PROBE_ORDER]
        lines.append(f'| {name} | {d.get("release", "?")} ({d.get("sdkInt", "?")}) | {"yes" if d.get("isEmulator") else "no"} | ' + " | ".join(cells) + " |")
    lines += ["", "## Details", ""]
    for e in evaluated:
        d = e["device"]
        lines.append(f'### {d.get("manufacturer", "?")} {d.get("model", "?")} (API {d.get("sdkInt", "?")}, patch {d.get("securityPatch", "?")})')
        for p in PROBE_ORDER:
            if p in e["results"]:
                st, summ = e["results"][p]
                lines.append(f"- **{p}** {st}: {summ}")
        if e["missing"]:
            lines.append("- Missing probes: " + ", ".join(e["missing"]))
        lines.append("")
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    ap.add_argument("--md")
    ap.add_argument("--json")
    args = ap.parse_args(argv)
    records, empty_files = scan_paths(args.paths)
    if not records:
        print("No PS_PROBE records found.", file=sys.stderr)
        return 2
    evaluated = evaluate(group_runs(records))
    md = to_markdown(evaluated, empty_files)
    if args.md:
        with open(args.md, "w") as fh:
            fh.write(md)
    else:
        print(md)
    if args.json:
        with open(args.json, "w") as fh:
            json.dump(evaluated, fh, indent=2, sort_keys=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
