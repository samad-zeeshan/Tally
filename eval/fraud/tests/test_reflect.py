"""
Reflection with a fake proposer: one proposal per run, the SMT check before the gate, every verdict logged.
"""

import sys
import unittest
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import episodes as E  # noqa: E402
import reflect  # noqa: E402

END = datetime(2026, 8, 31)


def row(pid, pattern, score, at, **features):
    f = {n: 0 for n in E.FEATURES}
    f.update(amount_minor=1_000, history_size=10, hour_seen_count=3, counterparty_known=1)
    f.update(features)
    return {"posting_id": pid, "seq": pid, "pattern": pattern, "episode": f"{pattern}-{pid}", "score": score,
            "rules": [], "at": at.strftime("%Y-%m-%dT%H:%M:%SZ"), "explanation": {"features": f}}


def rows():
    out = []
    for i in range(40):
        out.append(row(i + 1, "normal", 0, END - timedelta(days=10, minutes=i)))
    out.append(row(100, "mule_chain", 20, END - timedelta(days=6), seed_hops=2, passthrough_pct=95, incoming_60m_minor=50_000))
    out.append(row(101, "mule_chain", 20, END - timedelta(days=6), seed_hops=2, passthrough_pct=90, incoming_60m_minor=40_000))
    out.append(row(102, "normal", 45, END - timedelta(days=8), amount_to_median_x100=900))
    # Too recent for its label to have matured, so it must not reach the memory.
    out.append(row(103, "burst", 0, END - timedelta(days=1), outgoing_10m=5))
    return out


class FakeProposer:
    def __init__(self, *answers):
        self.answers = list(answers)
        self.prompts = []

    def __call__(self, prompt):
        self.prompts.append(prompt)
        return self.answers.pop(0)


class Reflect(unittest.TestCase):
    def setUp(self):
        self.memory = E.Memory.build(rows(), dataset_end=END, maturity=timedelta(days=3), run_id="t1")
        self.baseline = {"recall": 0.5, "auroc": 0.9, "flagged_normal": 50}

    def test_memory_holds_only_matured_errors(self):
        ids = {e["posting_id"] for e in self.memory.episodes}
        self.assertEqual({100, 101, 102}, ids)
        self.assertEqual({"missed": 2, "false_alert": 1}, self.memory.counts())

    def test_retrieval_brings_back_the_nearest_errors_of_each_kind(self):
        got = self.memory.retrieve(k=1)
        self.assertEqual(1, len(got["missed"]))
        self.assertEqual(1, len(got["false_alert"]))
        self.assertIn(got["missed"][0]["posting_id"], (100, 101))

    def test_a_rule_the_solver_refuses_never_reaches_the_gate(self):
        gate_calls = []
        proposer = FakeProposer('{"name": "known_small_payee", "feature": "counterparty_known", "op": "==", '
                                '"threshold": 1, "points": 40, "reason": "x"}')
        record = reflect.one_run(proposer, self.memory, self.baseline, [], gate=lambda r: gate_calls.append(r))
        self.assertEqual("rejected", record["smt"]["verdict"])
        self.assertEqual("not_run", record["gate"]["verdict"])
        self.assertEqual([], gate_calls)
        self.assertEqual("rejected", record["verdict"])

    def test_a_rule_the_solver_passes_goes_to_the_gate_and_both_verdicts_are_kept(self):
        proposer = FakeProposer('{"name": "second_hop_seed", "feature": "seed_hops", "op": ">=", '
                                '"threshold": 2, "points": 20, "reason": "mules two hops out"}')
        after = {"recall": 0.55, "auroc": 0.91, "flagged_normal": 50}
        record = reflect.one_run(proposer, self.memory, self.baseline, [], gate=lambda r: after)
        self.assertEqual("passed", record["smt"]["verdict"])
        self.assertEqual("accepted", record["gate"]["verdict"])
        self.assertEqual("accepted", record["verdict"])
        self.assertEqual("mules two hops out", record["reason"])
        self.assertEqual(1, len(proposer.prompts), "one proposal per run")

    def test_the_gate_refuses_a_rule_that_costs_false_alerts(self):
        proposer = FakeProposer('{"name": "second_hop_seed", "feature": "seed_hops", "op": ">=", '
                                '"threshold": 2, "points": 20, "reason": "r"}')
        after = {"recall": 0.6, "auroc": 0.95, "flagged_normal": 51}
        record = reflect.one_run(proposer, self.memory, self.baseline, [], gate=lambda r: after)
        self.assertEqual("rejected", record["gate"]["verdict"])
        self.assertIn("flagged normal postings rose from 50 to 51", record["gate"]["why"])

    def test_an_answer_that_is_not_a_rule_is_logged_as_a_rejection(self):
        record = reflect.one_run(FakeProposer("I think you should look at velocity."), self.memory,
                                 self.baseline, [], gate=lambda r: self.fail("no gate"))
        self.assertEqual("rejected", record["verdict"])
        self.assertEqual("unparseable", record["smt"]["reason"])

    def test_the_prompt_shows_retrieved_episodes_and_past_verdicts(self):
        self.memory.remember_proposal({"rule": {"name": "old_idea"}, "verdict": "rejected", "why": ["AUROC fell"]})
        proposer = FakeProposer('{"name": "second_hop_seed", "feature": "seed_hops", "op": ">=", '
                                '"threshold": 2, "points": 20, "reason": "r"}')
        reflect.one_run(proposer, self.memory, self.baseline, [], gate=lambda r: self.baseline)
        prompt = proposer.prompts[0]
        self.assertIn("old_idea", prompt)
        self.assertIn("AUROC fell", prompt)
        self.assertIn('"passthrough_pct": 95', prompt)


if __name__ == "__main__":
    unittest.main()
