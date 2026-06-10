"""
The point-in-time verifier passes clean features and catches a leaked one.
"""

import csv
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import features as F  # noqa: E402
import verify_pit  # noqa: E402

DATA = HERE.parents[2] / "data" / "fraud"


def sample_stream(n=900):
    with open(DATA / "transfers.csv", newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))[:n]
    return [F.Posting(int(r["seq"]), r["from"], r["to"], int(r["amount_minor"]), F.parse_time(r["event_time"]),
                      score=45 if int(r["seq"]) % 7 == 0 else 0) for r in rows]


def leaky(index, i):
    """outgoing_10m counted over the account's whole stream, the future included. A plausible slip."""
    f = F.features_at(index, i)
    e = index.stream[i]
    everyone = [index.stream[j] for j in index.out.get(e.src, []) if j != i]
    f["outgoing_10m"] = sum(1 for p in everyone if e.at - F.TEN_MIN <= p.at <= e.at + F.TEN_MIN)
    return f


class VerifyPointInTime(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.stream = sample_stream()
        index = F.Index(cls.stream)
        cls.recorded = {p.pid: F.features_at(index, i) for i, p in enumerate(cls.stream)}

    def test_clean_features_pass(self):
        report = verify_pit.verify(self.stream, self.recorded, samples=60)
        self.assertTrue(report["passed"], report["first_problems"])
        self.assertEqual(0, report["future_perturbation_leaks"])

    def test_a_feature_that_reads_the_future_is_caught(self):
        report = verify_pit.verify(self.stream, None, samples=60, feature_fn=leaky)
        self.assertFalse(report["passed"])
        self.assertGreater(report["future_perturbation_leaks"], 0)
        self.assertEqual({"outgoing_10m"}, {p["feature"] for p in report["first_problems"]})

    def test_a_recorded_value_the_stream_cannot_explain_is_caught(self):
        recorded = {pid: dict(f) for pid, f in self.recorded.items()}
        some = self.stream[500].pid
        recorded[some]["payee_payers"] += 1
        report = verify_pit.verify(self.stream, recorded, samples=5)
        self.assertEqual(1, report["java_python_mismatches"])
        self.assertEqual(some, report["first_problems"][0]["posting"])

    def test_evidence_from_a_later_posting_is_caught(self):
        pid = self.stream[100].pid
        report = verify_pit.verify(self.stream, self.recorded, evidence={pid: [pid - 3, pid + 1]}, samples=5)
        self.assertEqual(1, report["late_evidence"])
        self.assertFalse(report["passed"])

    def test_a_stream_out_of_commit_order_is_refused(self):
        with self.assertRaises(ValueError):
            F.Index(list(reversed(self.stream[:10])))


if __name__ == "__main__":
    unittest.main()
