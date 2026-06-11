"""
The SMT rule check: the live rule set satisfies every policy, and bad proposals are refused with a reason.
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import verify_rules as V  # noqa: E402


def rule(name, feature, op, threshold, points):
    return {"name": name, "feature": feature, "op": op, "threshold": threshold, "points": points}


class VerifyRules(unittest.TestCase):
    def test_the_rules_in_force_satisfy_every_policy(self):
        for policy in V.POLICIES:
            self.assertIsNone(V.counterexample(V.flagged_expr([]), policy), policy["name"])

    def test_a_rule_that_flags_small_payments_to_known_payees_is_a_contradiction(self):
        verdict = V.check(rule("known_small_payee", "counterparty_known", "==", 1, 40))
        self.assertEqual("rejected", verdict["verdict"])
        self.assertEqual("contradicts_policy", verdict["reason"])
        self.assertEqual("known_payee_small_payment", verdict["policy"])
        cx = verdict["counterexample"]
        self.assertEqual(1, cx["counterparty_known"])
        self.assertLessEqual(cx["amount_minor"], 20_000)

    def test_a_rule_that_can_never_fire_is_refused(self):
        verdict = V.check(rule("impossible", "counterparty_known", ">=", 2, 20))
        self.assertEqual(("rejected", "never_fires"), (verdict["verdict"], verdict["reason"]))

    def test_a_rule_that_flags_nothing_new_is_refused(self):
        # One point on top of what is already flagged, and nowhere near enough on its own.
        verdict = V.check(rule("tiny_bump", "amount_minor", ">=", 1, 1))
        self.assertEqual(("rejected", "adds_no_coverage"), (verdict["verdict"], verdict["reason"]))

    def test_a_plausible_mule_rule_is_refused_when_it_would_flag_a_small_known_payment(self):
        # Forwarding most of the hour's inflow looks like a mule, but a 200.00 payment to a known payee that
        # happens to pass on an incoming 250.00 would cross the line too. The solver finds that case.
        verdict = V.check(rule("mule_passthrough", "passthrough_pct", ">=", 80, 20))
        self.assertEqual(("rejected", "contradicts_policy"), (verdict["verdict"], verdict["reason"]))
        self.assertGreaterEqual(verdict["counterexample"]["passthrough_pct"], 80)

    def test_a_useful_rule_passes_with_a_witness_of_what_it_adds(self):
        verdict = V.check(rule("second_hop_seed", "seed_hops", ">=", 2, 20))
        self.assertEqual("passed", verdict["verdict"], verdict)
        self.assertEqual(2, verdict["coverage"]["witness"]["seed_hops"])

    def test_a_malformed_proposal_is_refused_before_the_solver(self):
        for bad in (rule("velocity", "amount_minor", ">=", 1, 10), rule("x", "amount_minor", ">=", 1, 10),
                    rule("ok_name", "no_such_feature", ">=", 1, 10), rule("ok_name", "amount_minor", "<", 1, 10),
                    rule("ok_name", "amount_minor", ">=", -1, 10), rule("ok_name", "amount_minor", ">=", 1, 41)):
            self.assertEqual(("rejected", "malformed"), (V.check(bad)["verdict"], V.check(bad)["reason"]), bad)

    def test_empirical_coverage_counts_newly_flagged_postings(self):
        rows = [{"pattern": "mule_chain", "score": 25, "features": {"passthrough_pct": 95}},
                {"pattern": "normal", "score": 25, "features": {"passthrough_pct": 90}},
                {"pattern": "normal", "score": 0, "features": {"passthrough_pct": 99}},
                {"pattern": "mule_chain", "score": 50, "features": {"passthrough_pct": 99}}]
        cov = V.empirical_coverage(rule("mule_passthrough", "passthrough_pct", ">=", 80, 20), rows)
        self.assertEqual({"newly_flagged_fraud": 1, "newly_flagged_normal": 1}, cov)


if __name__ == "__main__":
    unittest.main()
