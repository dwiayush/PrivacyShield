import json, os, sys, tempfile, unittest
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "tools"))
import aggregate_probes as ap
import pick_ftl_devices as pf


def line(run, probe, data, fmt="threadtime"):
    j = json.dumps({"schema": 1, "run": run, "probe": probe, "data": data})
    return f"09-29 10:00:01.123  4242  4242 I PS_PROBE: {j}" if fmt == "threadtime" else f"I/PS_PROBE( 4242): {j}"


DEV = {"manufacturer": "samsung", "model": "SM-A15", "sdkInt": 34, "release": "14", "securityPatch": "2026-05-01", "isEmulator": False}
GOOD = {
    "S1": {"holdsQueryAllPackages": True, "countNoFlags": 120, "countWithPermissions": 120, "systemCount": 80, "userCount": 40, "msWithPermissions": 300},
    "S3": {op: {"user": {"0": 1, "3": 39}, "system": {"3": 80}} for op in ["OVERLAY", "USAGE_ACCESS", "INSTALL_UNKNOWN", "ALL_FILES"]},
    "S6": {"error": "java.lang.SecurityException", "message": "denied"},
    "S13": {"requested": ["android.permission.QUERY_ALL_PACKAGES", "app.privacyshield.spike.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"]},
    "S16": {"ACCESSIBILITY": True, "OVERLAY": True},
}


class AggregateTests(unittest.TestCase):
    def test_parses_both_logcat_formats_and_ignores_garbage(self):
        text = "\n".join([
            "random noise", line("r1", "DEVICE", DEV), line("r1", "S1", GOOD["S1"], "brief"),
            'I PS_PROBE: {"run": "r1", "probe": "S2", "data": {"trunc',  # truncated JSON
            "PS_PROBE with no json", 'I PS_PROBE: {"unrelated": 1}',
        ])
        recs = ap.parse_text(text)
        self.assertEqual([r["probe"] for r in recs], ["DEVICE", "S1"])

    def test_verdicts_follow_hypotheses(self):
        self.assertEqual(ap.verdict("S1", GOOD["S1"])[0], "PASS")
        self.assertEqual(ap.verdict("S3", GOOD["S3"])[0], "PASS")
        self.assertEqual(ap.verdict("S13", GOOD["S13"])[0], "PASS")
        self.assertEqual(ap.verdict("S16", GOOD["S16"])[0], "PASS")

    def test_s6_passes_when_blocked_and_fails_when_data_leaks(self):
        self.assertEqual(ap.verdict("S6", GOOD["S6"])[0], "PASS")
        self.assertEqual(ap.verdict("S6", {"blocked": False, "returnedEntries": 12})[0], "FAIL")

    def test_s3_flags_unreadable_ops(self):
        d = json.loads(json.dumps(GOOD["S3"]))
        d["ALL_FILES"]["user"] = {"exc:IllegalArgumentException": 40}
        st, summ = ap.verdict("S3", d)
        self.assertEqual(st, "FAIL"); self.assertIn("ALL_FILES", summ)

    def test_s1_permission_binder_failure_is_a_fail(self):
        d = dict(GOOD["S1"], permsError="android.os.TransactionTooLargeException", countWithPermissions=0)
        self.assertEqual(ap.verdict("S1", d)[0], "FAIL")

    def test_s13_flags_unexpected_permission(self):
        st, summ = ap.verdict("S13", {"requested": ["android.permission.QUERY_ALL_PACKAGES", "android.permission.INTERNET"]})
        self.assertEqual(st, "FAIL"); self.assertIn("INTERNET", summ)

    def test_s16_reports_missing_settings_screens(self):
        st, summ = ap.verdict("S16", {"ACCESSIBILITY": True, "OVERLAY": False})
        self.assertEqual(st, "FAIL"); self.assertIn("OVERLAY", summ)

    def test_errors_are_reported_not_hidden(self):
        self.assertEqual(ap.verdict("S4", {"error": "java.lang.SecurityException"})[0], "ERROR")

    def test_end_to_end_two_devices_interleaved_and_markdown(self):
        d2 = dict(DEV, manufacturer="Xiaomi", model="23021RAA2Y", sdkInt=33, isEmulator=False)
        text = "\n".join([line("a", "DEVICE", DEV), line("b", "DEVICE", d2), line("a", "S1", GOOD["S1"]),
                           line("b", "S1", dict(GOOD["S1"], holdsQueryAllPackages=False)), line("a", "S13", GOOD["S13"])])
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "logcat"), "w") as fh:
                fh.write(text)
            out = os.path.join(d, "out.md"); js = os.path.join(d, "out.json")
            self.assertEqual(ap.main([d, "--md", out, "--json", js]), 0)
            with open(out) as fh:
                md = fh.read()
            self.assertIn("samsung SM-A15", md); self.assertIn("Xiaomi 23021RAA2Y", md)
            self.assertIn("Missing probes", md)  # partial runs are visible, never silently complete
            with open(js) as fh:
                data = json.load(fh)
            self.assertEqual(len(data), 2)
            by = {e["device"]["manufacturer"]: e for e in data}
            self.assertEqual(by["samsung"]["results"]["S1"][0], "PASS")
            self.assertEqual(by["Xiaomi"]["results"]["S1"][0], "FAIL")

    def test_no_records_exits_nonzero_so_ci_notices(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "logcat"), "w") as fh:
                fh.write("nothing here")
            self.assertEqual(ap.main([d]), 2)


def model(id_, mfr, versions, form="PHYSICAL", ff="PHONE", tags=None, brand=None):
    return {"id": id_, "manufacturer": mfr, "brand": brand or mfr, "form": form, "formFactor": ff,
            "supportedVersionIds": [str(v) for v in versions], "tags": tags or []}


CATALOG = [
    model("a15", "Samsung", [33, 34]), model("a03", "Samsung", [30, 31]), model("s25", "Samsung", [35, 36]),
    model("redmi9", "Xiaomi", [29, 30]), model("redmi13", "Xiaomi", [34, 35], brand="Redmi"),
    model("virt1", "Samsung", [23], form="VIRTUAL"), model("tab", "Samsung", [24], ff="TABLET"),
    model("dead", "Samsung", [21], tags=["deprecated"]), model("noversions", "Xiaomi", []),
    model("tecno1", "Tecno", [30, 31]), model("pixel", "Google", [34, 35, 36]),
    model("random", "SomeUnknownBrand", [30]), {"weird": "schema"},
]


class PickerTests(unittest.TestCase):
    def test_excludes_virtual_tablet_deprecated_and_malformed(self):
        ids = {m["id"] for m in CATALOG if pf.eligible(m)}
        for bad in ("virt1", "tab", "dead", "noversions"):
            self.assertNotIn(bad, ids)
        self.assertFalse(pf.eligible({"weird": "schema"}))

    def test_picks_old_and_new_per_family_in_priority_order(self):
        chosen = pf.pick(CATALOG, 20)
        self.assertEqual(chosen[0], ("a03", 30)); self.assertEqual(chosen[1], ("s25", 36))  # samsung old, new
        self.assertEqual(chosen[2], ("redmi9", 29)); self.assertEqual(chosen[3], ("redmi13", 35))
        self.assertIn(("tecno1", 30), chosen)
        self.assertNotIn("random", [c[0] for c in chosen])  # unknown brand not in a family

    def test_respects_max_and_has_no_duplicates(self):
        chosen = pf.pick(CATALOG, 3)
        self.assertEqual(len(chosen), 3); self.assertEqual(len(set(chosen)), 3)

    def test_empty_or_bad_schema_fails_loudly(self):
        self.assertEqual(pf.pick([{"weird": "schema"}], 5), [])
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            f.write("not json")
        self.assertEqual(pf.main([f.name]), 2)
        os.unlink(f.name)


if __name__ == "__main__":
    unittest.main()
