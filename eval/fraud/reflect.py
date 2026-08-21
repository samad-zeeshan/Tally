"""
Offline reflection: read matured errors from episodic memory, ask a local model for one rule, check it
with z3, and only then let the replay gate decide.

One proposal per run, never in the service's request path. Every proposal and both verdicts are logged.
"""

import argparse
import json
import re
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import z3

sys.path.insert(0, str(Path(__file__).resolve().parent))
import episodes as E  # noqa: E402
import run  # noqa: E402
import verify_rules as V  # noqa: E402

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]

CATALOG = {
    "outgoing_10m": "earlier payments out by this account in the last 10 minutes",
    "outgoing_60m": "earlier payments out by this account in the last 60 minutes",
    "amount_minor": "the payment amount in minor units (cents)",
    "amount_to_median_x100": "amount * 100 / median of the account's earlier amounts, 0 if fewer than 5 earlier",
    "history_size": "earlier payments out by this account, up to 200",
    "counterparty_known": "1 if the account has paid this payee before, else 0",
    "distinct_payees_60m": "distinct payees of earlier payments out in the last 60 minutes",
    "hour_seen_count": "earlier payments out within one hour of day (UTC) of this one",
    "incoming_60m_minor": "money paid into this account in the last 60 minutes, minor units",
    "passthrough_pct": "amount * 100 / incoming_60m_minor, 0 if nothing came in",
    "fan_in_60m": "distinct accounts that paid this account in the last 60 minutes",
    "payee_payers": "distinct accounts that have ever paid the payee before, counted up to 50",
    "payee_outgoing": "payments the payee has ever made, up to 200",
    "flagged_in_60m_minor": "money into this account in the last 60 minutes from payments already flagged",
    "seed_hops": "1 if flagged money came in within the hour, 2 if it reached one of this account's payers, else 0",
    "cycle_24h": "1 if money went from the payee back to this account within a day, in up to 3 hops",
}

RULES_IN_FORCE = """- velocity: 3+ earlier payments out in 10 minutes, 15 to 35 points
- amount_deviation: amount at least 5x the account's median, 25 to 35 points
- new_counterparty: a payee not paid before, once 3+ payments of history, 20 points
- round_amount: whole hundreds from 500.00 up, 15 points
- time_of_day: an hour with no earlier payment within one hour, once 10+ payments of history, 20 points
- fresh_payee_burst: 2+ payments out in 10 minutes to a payee with at most 1 payer and no payments out, 30 points
- forwards_flagged: sends on half or more of flagged money that came in within the hour, 40 points (25 two hops out)
- cycle_24h: money came back from the payee within a day, 25 points
- established_payee: the payee has 3+ payers already, minus 20 points"""

PROMPT = """You improve a deterministic payment fraud scorer. Each payment gets points from rules, and a payment
with 40 points or more is flagged. The rules in force:
{rules}

Propose exactly ONE new rule that adds points. It should flag more of the missed fraud below without
flagging more normal payments. A rule that only reaches 40 together with existing rules is fine. The
feature catalog, nothing else may be used:
{catalog}

Matured errors from memory, nearest to the middle of the missed fraud (non-zero features only):
Missed fraud:
{missed}
False alerts:
{false_alerts}

Memory holds {counts} matured errors in total. Earlier proposals and what happened to them:
{history}

Answer with one JSON object: name (lowercase, 3 to 40 of a-z 0-9 _), feature, op (">=", "<=" or "=="),
threshold (whole number, not negative), points (1 to 40), reason (one sentence)."""

SCHEMA = {
    "type": "object",
    "additionalProperties": False,
    "required": ["name", "feature", "op", "threshold", "points", "reason"],
    "properties": {
        "name": {"type": "string", "pattern": "^[a-z][a-z0-9_]{2,39}$"},
        "feature": {"type": "string", "enum": list(CATALOG)},
        "op": {"type": "string", "enum": [">=", "<=", "=="]},
        "threshold": {"type": "integer", "minimum": 0},
        "points": {"type": "integer", "minimum": 1, "maximum": 40},
        "reason": {"type": "string"},
    },
}


class LMStudio:
    """A proposer backed by a local model, with the answer held to SCHEMA by LM Studio's structured output."""

    def __init__(self, model, url):
        self.model, self.url = model, url
        self.errors = []

    def __call__(self, prompt):
        # No thinking: the server is shared, and a thinking answer took longer than the 15-minute timeout.
        body = json.dumps({"model": self.model, "temperature": 0.2, "max_tokens": 600, "reasoning_effort": "none",
                           "messages": [{"role": "user", "content": prompt}],
                           "response_format": {"type": "json_schema",
                                               "json_schema": {"name": "rule", "strict": True, "schema": SCHEMA}}}).encode()
        # The server is shared, and under load it answers "terminated" or a 500. That is the server, not
        # the proposal, so it is retried and kept on the record rather than counted as a rejected rule.
        for attempt in range(1, 4):
            req = urllib.request.Request(self.url + "/chat/completions", data=body,
                                         headers={"Content-Type": "application/json"})
            try:
                with urllib.request.urlopen(req, timeout=900) as r:
                    message = json.loads(r.read())["choices"][0]["message"]
                return message.get("content") or message.get("reasoning_content") or ""
            except (urllib.error.URLError, TimeoutError) as e:
                self.errors.append(str(e)[:200])
                if attempt == 3:
                    raise
                time.sleep(30 * attempt)


def parse(text):
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    match = re.search(r"\{.*\}", text, flags=re.S)
    if not match:
        raise ValueError("no JSON object in the answer")
    obj = json.loads(match.group(0))
    reason = obj.pop("reason", "")
    return obj, reason


def gate_verdict(before, after):
    """Accept only if recall rises, AUROC does not fall, and no more normal postings are flagged."""
    why = []
    if not after["recall"] > before["recall"]:
        why.append("recall did not rise")
    if after["auroc"] < before["auroc"]:
        why.append("AUROC fell")
    if after["flagged_normal"] > before["flagged_normal"]:
        why.append(f"flagged normal postings rose from {before['flagged_normal']} to {after['flagged_normal']}")
    return ("accepted", []) if not why else ("rejected", why)


def compact(episode):
    return json.dumps({"pattern": episode["pattern"], "score": episode["score"],
                       **{k: v for k, v in episode["features"].items() if v}})


def prompt_for(memory, accepted):
    got = memory.retrieve()
    history = "\n".join(f"- {p['rule'].get('name') if p.get('rule') else 'no rule'} -> {p['verdict']}"
                        f" ({'; '.join(p.get('why') or []) or 'improved'})" for p in memory.proposals) or "- none yet"
    rules = RULES_IN_FORCE + "".join(f"\n- {r['name']}: {r['feature']} {r['op']} {r['threshold']}, {r['points']} points"
                                     for r in accepted)
    text = PROMPT.format(rules=rules, catalog="\n".join(f"- {k}: {v}" for k, v in CATALOG.items()),
                         missed="\n".join(compact(e) for e in got["missed"]) or "(none)",
                         false_alerts="\n".join(compact(e) for e in got["false_alert"]) or "(none)",
                         counts=sum(memory.counts().values()), history=history)
    return text, [e["id"] for kind in got.values() for e in kind]


def one_run(proposer, memory, baseline, accepted, gate):
    """One proposal: ask, check with z3, replay only if z3 passed it. Returns the full record."""
    prompt, used = prompt_for(memory, accepted)
    record = {"retrieved_episodes": used, "memory": memory.counts(), "before": baseline}
    try:
        answer = proposer(prompt)
    except Exception as e:  # the server, not the proposal, so it is recorded and the run ends cleanly
        record.update(rule=None, smt={"verdict": "rejected", "reason": "no_answer", "detail": f"{type(e).__name__}: {e}"[:300]},
                      gate={"verdict": "not_run"}, verdict="rejected", why=["the model did not answer"])
        memory.remember_proposal(record)
        return record
    try:
        rule, record["reason"] = parse(answer)
        record["rule"] = rule
    except (ValueError, json.JSONDecodeError) as e:
        record.update(rule=None, smt={"verdict": "rejected", "reason": "unparseable", "detail": str(e)},
                      gate={"verdict": "not_run"}, verdict="rejected", why=["the answer was not a rule"])
        memory.remember_proposal(record)
        return record
    record["smt"] = V.check(rule, accepted)
    if record["smt"]["verdict"] != "passed":
        record.update(gate={"verdict": "not_run"}, verdict="rejected",
                      why=[f"smt: {record['smt']['reason']}: {record['smt'].get('detail', '')}"])
        memory.remember_proposal(record)
        return record
    after = gate(rule)
    verdict, why = gate_verdict(baseline, after)
    record.update(gate={"verdict": verdict, "why": why, "after": after}, verdict=verdict, why=why)
    memory.remember_proposal(record)
    return record


def summary(result):
    a = result["all_fraud"]
    return {"recall": a["recall"], "auroc": a["auroc"], "flagged_fraud": a["flagged"],
            "flagged_normal": result["normal"]["flagged"]}


def parity(rows):
    """The z3 encoding must score every recorded posting exactly as Java did, or its verdicts mean nothing."""
    terms = list(V.built_in_points().values())
    total = z3.Sum(terms)
    bad = 0
    for r in rows:
        f = r["explanation"]["features"]
        value = z3.simplify(z3.substitute(total, *[(V.X[n], z3.IntVal(f[n])) for n in V.NAMES])).as_long()
        bad += max(0, min(100, value)) != r["score"]
    return bad


def main():
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--jar", type=Path, default=ROOT / "target" / "tally.jar")
    parser.add_argument("--data", type=Path, default=ROOT / "data" / "fraud")
    parser.add_argument("--threshold", type=int, default=40)
    parser.add_argument("--model", default="qwen/qwen3.5-9b")
    parser.add_argument("--llm-url", default="http://127.0.0.1:1234/v1")
    args = parser.parse_args()

    results = json.loads((HERE / "results.json").read_text(encoding="utf-8"))
    rows = [json.loads(line) for line in (HERE / "scores-v2.jsonl").read_text(encoding="utf-8").splitlines()]
    mismatches = parity(rows)
    if mismatches:
        raise SystemExit(f"the z3 encoding disagrees with the Java scores on {mismatches} postings")
    print(f"z3 parity: the encoding scores all {len(rows)} postings exactly as the service did")

    meta = json.loads((args.data / "meta.json").read_text(encoding="utf-8"))
    memory = E.Memory.load(HERE / "memory.json")
    memory.merge(E.Memory.build(rows, E.dataset_end(meta), run_id=results["code_version"]))
    accepted = json.loads((HERE / "accepted-rules.json").read_text(encoding="utf-8"))
    log_path = HERE / "reflection.json"
    log = json.loads(log_path.read_text(encoding="utf-8")) if log_path.exists() else {}
    runs = log.get("runs", [])
    baseline = runs[-1]["gate"]["after"] if runs and runs[-1]["verdict"] == "accepted" else summary(results["v2"])

    def gate(rule):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
            json.dump(accepted + [rule], f)
        result, _ = run.evaluate(args.jar, args.data, args.threshold, {"TALLY_FRAUD_RULESET": "v2",
                                                                         "TALLY_FRAUD_EXTRA_RULES": f.name})
        return summary(result)

    proposer = LMStudio(args.model, args.llm_url)
    record = one_run(proposer, memory, baseline, accepted, gate)
    record.update(run=len(runs) + 1, model=args.model, server_errors=proposer.errors,
                  at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    runs.append(record)
    if record["verdict"] == "accepted":
        accepted.append(record["rule"])
    log.update(z3_parity_postings=len(rows), runs=runs)
    log_path.write_text(json.dumps(log, indent=2) + "\n", encoding="utf-8", newline="\n")
    (HERE / "accepted-rules.json").write_text(json.dumps(accepted, indent=2) + "\n", encoding="utf-8", newline="\n")
    memory.save(HERE / "memory.json")
    print(json.dumps({k: record.get(k) for k in ("run", "rule", "verdict", "why")}, indent=1))


if __name__ == "__main__":
    main()
