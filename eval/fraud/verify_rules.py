"""
Check a proposed fraud rule against the rules in force with z3, before any replay runs.

After ActGov (arXiv 2609.24446): a rule update is accepted only if no counterexample breaks a stated
policy. Also refuses a rule that can never fire or flags nothing new, and reports what it would add.
"""

import re

import z3

FLAG = 40
NAMES = ["outgoing_10m", "outgoing_60m", "amount_minor", "amount_to_median_x100", "history_size",
         "counterparty_known", "distinct_payees_60m", "hour_seen_count", "incoming_60m_minor", "passthrough_pct",
         "fan_in_60m", "payee_payers", "payee_outgoing", "flagged_in_60m_minor", "seed_hops", "cycle_24h"]
BUILT_IN = ["velocity", "amount_deviation", "new_counterparty", "round_amount", "time_of_day",
            "fresh_payee_burst", "forwards_flagged", "cycle_24h", "established_payee"]
NAME = re.compile(r"[a-z][a-z0-9_]{2,39}")

X = {n: z3.Int(n) for n in NAMES}


def domain():
    """What any real posting's features satisfy, from how Scorer and FeatureRule compute them."""
    x = X
    return z3.And(
        *[x[n] >= 0 for n in NAMES],
        x["amount_minor"] >= 1, x["amount_minor"] <= 10**12,
        x["counterparty_known"] <= 1, x["cycle_24h"] <= 1, x["seed_hops"] <= 2,
        x["outgoing_10m"] <= x["outgoing_60m"], x["outgoing_60m"] <= x["history_size"], x["history_size"] <= 200,
        x["distinct_payees_60m"] <= x["outgoing_60m"], x["hour_seen_count"] <= x["history_size"],
        x["payee_outgoing"] <= 200,
        z3.Implies(x["history_size"] < 5, x["amount_to_median_x100"] == 0),
        z3.Implies(x["counterparty_known"] == 1, z3.And(x["history_size"] >= 1, x["payee_payers"] >= 1)),
        z3.Implies(x["incoming_60m_minor"] == 0, z3.And(x["passthrough_pct"] == 0, x["fan_in_60m"] == 0)),
        z3.Implies(x["incoming_60m_minor"] > 0, x["fan_in_60m"] >= 1),
        z3.Implies(x["incoming_60m_minor"] > 0, x["passthrough_pct"] == (100 * x["amount_minor"]) / x["incoming_60m_minor"]),
        z3.Implies(x["outgoing_60m"] >= 1, x["distinct_payees_60m"] >= 1),
        x["flagged_in_60m_minor"] <= x["incoming_60m_minor"],
        (x["seed_hops"] == 1) == (x["flagged_in_60m_minor"] > 0),
    )


def built_in_points():
    """The nine rules in force, Rules.V2, as z3 terms. Each mirrors its Java record line for line."""
    x = X
    o10, hist, amt = x["outgoing_10m"], x["history_size"], x["amount_minor"]
    ratio = x["amount_to_median_x100"]
    return {
        "velocity": z3.If(o10 >= 3, z3.If(15 + 5 * (o10 - 3) >= 35, 35, 15 + 5 * (o10 - 3)), 0),
        # amount >= 5 x median is the same line as amount * 100 / median >= 500 in integer division.
        "amount_deviation": z3.If(z3.And(hist >= 5, ratio >= 500),
                                  z3.If(ratio >= 2000, 35, z3.If(ratio >= 1000, 30, 25)), 0),
        "new_counterparty": z3.If(z3.And(hist >= 3, x["counterparty_known"] == 0), 20, 0),
        "round_amount": z3.If(z3.And(amt >= 50_000, amt % 10_000 == 0), 15, 0),
        "time_of_day": z3.If(z3.And(hist >= 10, x["hour_seen_count"] == 0), 20, 0),
        "fresh_payee_burst": z3.If(z3.And(o10 >= 2, x["payee_payers"] <= 1, x["payee_outgoing"] == 0), 30, 0),
        "forwards_flagged": z3.If(z3.And(x["seed_hops"] == 1, 100 * amt >= 50 * x["flagged_in_60m_minor"]), 40,
                                  z3.If(z3.And(x["seed_hops"] == 2, x["passthrough_pct"] >= 50), 25, 0)),
        "cycle_24h": z3.If(x["cycle_24h"] == 1, 25, 0),
        "established_payee": z3.If(x["payee_payers"] >= 3, -20, 0),
    }


def fires(rule):
    v, t = X[rule["feature"]], rule["threshold"]
    return {">=": v >= t, "<=": v <= t, "==": v == t}[rule["op"]]


def flagged_expr(extra):
    total = z3.Sum(list(built_in_points().values()) + [z3.If(fires(r), r["points"], 0) for r in extra])
    return total >= FLAG


# Stated policies, each a region that must never be flagged. The rules in force satisfy all of them, which
# a test checks, so a counterexample under a proposal is the proposal's doing.
POLICIES = [
    {"name": "known_payee_small_payment",
     "says": "a payment of 200.00 or less to a payee the account already pays, at a usual hour, with no burst "
             "and no flagged money or loop behind it, is never flagged",
     "region": lambda x: z3.And(x["counterparty_known"] == 1, x["amount_minor"] <= 20_000, x["outgoing_10m"] <= 1,
                                x["hour_seen_count"] >= 1, x["seed_hops"] == 0, x["cycle_24h"] == 0)},
    {"name": "first_payment_small",
     "says": "an account's first payment, under 500.00, with no flagged money or loop behind it, is never flagged",
     "region": lambda x: z3.And(x["history_size"] == 0, x["amount_minor"] < 50_000, x["seed_hops"] == 0,
                                x["cycle_24h"] == 0)},
]


def model_of(solver):
    m = solver.model()
    return {n: m.eval(X[n], model_completion=True).as_long() for n in NAMES}


def counterexample(flag, policy):
    s = z3.Solver()
    s.add(domain(), policy["region"](X), flag)
    return model_of(s) if s.check() == z3.sat else None


def malformed(rule):
    if not isinstance(rule, dict) or set(rule) != {"name", "feature", "op", "threshold", "points"}:
        return "a rule is exactly name, feature, op, threshold and points"
    if not isinstance(rule["name"], str) or not NAME.fullmatch(rule["name"]) or rule["name"] in BUILT_IN:
        return "the name must be 3 to 40 of a-z, 0-9 and _, and not a rule in force"
    if rule["feature"] not in NAMES:
        return f"unknown feature {rule['feature']!r}"
    if rule["op"] not in (">=", "<=", "=="):
        return "op must be >=, <= or =="
    if not isinstance(rule["threshold"], int) or rule["threshold"] < 0:
        return "threshold must be a whole number, not negative"
    if not isinstance(rule["points"], int) or not 1 <= rule["points"] <= 40:
        return "points must be 1 to 40"
    return None


def check(rule, in_force=()):
    """The verdict for one proposal against the rules in force plus any already accepted extras."""
    why = malformed(rule)
    if why:
        return {"verdict": "rejected", "reason": "malformed", "detail": why}
    s = z3.Solver()
    s.add(domain(), fires(rule))
    if s.check() != z3.sat:
        return {"verdict": "rejected", "reason": "never_fires", "detail": "no real posting satisfies the condition"}
    before, after = flagged_expr(list(in_force)), flagged_expr(list(in_force) + [rule])
    for policy in POLICIES:
        cx = counterexample(after, policy)
        if cx is not None:
            return {"verdict": "rejected", "reason": "contradicts_policy", "policy": policy["name"],
                    "detail": policy["says"], "counterexample": cx}
    s = z3.Solver()
    s.add(domain(), after, z3.Not(before))
    if s.check() != z3.sat:
        return {"verdict": "rejected", "reason": "adds_no_coverage",
                "detail": "every posting it would flag is flagged already"}
    return {"verdict": "passed", "coverage": {"widens": True, "witness": model_of(s)}}


def empirical_coverage(rule, rows):
    """How many scored postings from the last evaluation the rule would newly push over the flag line."""
    out = {"newly_flagged_fraud": 0, "newly_flagged_normal": 0}
    for r in rows:
        v = r["features"].get(rule["feature"], 0)
        hit = {">=": v >= rule["threshold"], "<=": v <= rule["threshold"], "==": v == rule["threshold"]}[rule["op"]]
        if hit and r["score"] < FLAG <= r["score"] + rule["points"]:
            out["newly_flagged_normal" if r["pattern"] == "normal" else "newly_flagged_fraud"] += 1
    return out
