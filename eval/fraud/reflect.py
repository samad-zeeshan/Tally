"""
Offline reflection in the style of SR-Fraud: show a local model the scorer's matured errors, ask it for
one new rule as constrained JSON, and let a deterministic gate decide whether to keep it.

Every proposal and verdict goes to eval/fraud/reflection.json. See ADR-0025.
"""

import argparse
import bisect
import json
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run  # noqa: E402  the evaluation this gate is built on

ROOT = Path(__file__).resolve().parents[2]
FEATURES = {
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
}
WINDOW_LIMIT = 200


def parse_time(s):
    return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ")


def features(transfers):
    """The Java feature catalog, recomputed from the dataset in replay order, one dict per seq."""
    out_by = {}     # account -> list of (time, to, amount), oldest first
    in_by = {}      # account -> list of (time, amount)
    result = {}
    for t in transfers:
        at, src, dst, amount = parse_time(t["event_time"]), t["from"], t["to"], int(t["amount_minor"])
        history = out_by.get(src, [])[-WINDOW_LIMIT:]
        amounts = sorted(a for _, _, a in history)
        median = amounts[(len(amounts) - 1) // 2] if amounts else 0
        ten, hour = at - timedelta(minutes=10), at - timedelta(hours=1)
        inflow = sum(a for when, a in in_by.get(src, []) if when >= hour)
        near = 0
        for when, _, _ in history:
            d = abs(when.hour - at.hour)
            near += min(d, 24 - d) <= 1
        f = {
            "outgoing_10m": sum(1 for when, _, _ in history if ten <= when <= at),
            "outgoing_60m": sum(1 for when, _, _ in history if hour <= when <= at),
            "amount_minor": amount,
            "amount_to_median_x100": 0 if len(history) < 5 else amount * 100 // max(1, median),
            "history_size": len(history),
            "counterparty_known": int(any(to == dst for _, to, _ in history)),
            "distinct_payees_60m": len({to for when, to, _ in history if hour <= when <= at}),
            "hour_seen_count": near,
            "incoming_60m_minor": inflow,
            "passthrough_pct": 0 if inflow == 0 else amount * 100 // inflow,
            "_median": median,
        }
        result[t["seq"]] = f
        out_by.setdefault(src, []).append((at, dst, amount))
        in_by.setdefault(dst, []).append((at, amount))
    return result


def baseline_score(f):
    """The five Java rules, from the features. Used only to prove the evidence matches the service."""
    total = 0
    n = f["outgoing_10m"]
    if n >= 3:
        total += min(35, 15 + 5 * (n - 3))
    median = max(1, f["_median"])
    if f["history_size"] >= 5 and f["amount_minor"] >= 5 * median:
        ratio = f["amount_minor"] // median
        total += 35 if ratio >= 20 else 30 if ratio >= 10 else 25
    if f["history_size"] >= 3 and not f["counterparty_known"]:
        total += 20
    if f["amount_minor"] >= 50_000 and f["amount_minor"] % 10_000 == 0:
        total += 15
    if f["history_size"] >= 10 and f["hour_seen_count"] == 0:
        total += 20
    return min(100, total)


def quantiles(values):
    if not values:
        return None
    v = sorted(values)
    pick = lambda q: v[min(len(v) - 1, int(q * (len(v) - 1)))]
    return {"p10": pick(0.1), "p50": pick(0.5), "p90": pick(0.9)}


def evidence(rows, feats, threshold):
    """A typed evidence pack, in the BENCHCOMPASS sense: counts and distributions, never raw ids."""
    fn = [r for r in rows if r["pattern"] != "normal" and r["score"] < threshold]
    fp = [r for r in rows if r["pattern"] == "normal" and r["score"] >= threshold]
    normal = [r for r in rows if r["pattern"] == "normal"]
    pack = {"threshold": threshold, "false_negatives_by_pattern": {}, "false_positives": len(fp),
            "feature_distributions": {}}
    for r in fn:
        pack["false_negatives_by_pattern"][r["pattern"]] = pack["false_negatives_by_pattern"].get(r["pattern"], 0) + 1
    groups = {"normal": normal, "false_positives": fp}
    for p in run.PATTERNS:
        groups["missed_" + p] = [r for r in fn if r["pattern"] == p]
    for name in FEATURES:
        pack["feature_distributions"][name] = {
            g: quantiles([feats[str(r["seq"])][name] for r in members]) for g, members in groups.items() if members
        }
    return pack


PROMPT = """You improve a deterministic payment fraud scorer. Each payment gets points from rules; a payment
with 40 points or more is flagged. The current rules are:
- velocity: 3+ earlier payments out in 10 minutes, 15 to 35 points
- amount_deviation: amount at least 5x the account's median, 25 to 35 points
- new_counterparty: a payee not paid before, once 3+ payments of history, 20 points
- round_amount: whole hundreds from 500.00 up, 15 points
- time_of_day: an hour with no earlier payment within one hour, once 10+ payments of history, 20 points

Propose exactly ONE new rule that would flag more of the missed fraud without flagging more normal
payments. A rule adds its points to the others, so a rule that only reaches 40 together with an
existing rule is fine. You may only use these features:
{features}

Evidence from the latest evaluation (p10/p50/p90 of each feature, per group):
{evidence}

Earlier proposals and the gate's verdicts:
{history}

Answer with one JSON object and nothing else, in this exact shape:
{{"name": "lowercase_name", "feature": "<one feature above>", "op": ">=" or "<=" or "==",
  "threshold": <non-negative integer>, "points": <integer 1 to 40>, "reason": "<one sentence>"}}"""


def ask(model, prompt, url):
    body = json.dumps({"model": model, "temperature": 0.2, "max_tokens": 12000,
                       "messages": [{"role": "user", "content": prompt}]}).encode()
    req = urllib.request.Request(url + "/chat/completions", data=body, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=900) as r:
            message = json.loads(r.read())["choices"][0]["message"]
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"LM Studio answered {e.code}: {e.read().decode('utf-8', 'replace')[:300]}") from None
    # A thinking model can spend its whole budget reasoning and leave content empty; the answer is often
    # still at the end of the reasoning, so look there before giving up.
    return message.get("content") or message.get("reasoning_content") or ""


def ask_with_retry(model, prompt, url, entry, attempts=3):
    # The LM Studio server is shared, and under load it answers "terminated" or a 500. That is the
    # server, not the proposal, so it is retried and recorded rather than counted as a rejected rule.
    for attempt in range(1, attempts + 1):
        try:
            return ask(model, prompt, url)
        except RuntimeError as e:
            entry.setdefault("server_errors", []).append(str(e)[:200])
            if attempt == attempts:
                raise
            time.sleep(30 * attempt)


def extract(text):
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.S)
    match = re.search(r"\{.*\}", text, flags=re.S)
    if not match:
        raise ValueError("no JSON object in the answer")
    obj = json.loads(match.group(0))
    reason = obj.pop("reason", "")
    return obj, reason


def summary(result):
    a = result["all_fraud"]
    return {"recall": a["recall"], "auroc": a["auroc"], "flagged_fraud": a["flagged"],
            "flagged_normal": result["normal"]["flagged"],
            "by_pattern_recall": {p: m["recall"] for p, m in result["patterns"].items()}}


def gate(before, after):
    """Accept only if recall rises, AUROC does not fall, and no more normal postings are flagged."""
    reasons = []
    if not after["recall"] > before["recall"]:
        reasons.append("recall did not rise")
    if after["auroc"] < before["auroc"]:
        reasons.append("AUROC fell")
    if after["flagged_normal"] > before["flagged_normal"]:
        reasons.append(f"flagged normal postings rose from {before['flagged_normal']} to {after['flagged_normal']}")
    return ("accepted", []) if not reasons else ("rejected", reasons)


def evaluate_with(jar, data, threshold, rules):
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as f:
        json.dump(rules, f)
        path = f.name
    return run.evaluate(jar, data, threshold, {"TALLY_FRAUD_EXTRA_RULES": path})


def main():
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--jar", type=Path, default=ROOT / "target" / "tally.jar")
    parser.add_argument("--data", type=Path, default=ROOT / "data" / "fraud")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--threshold", type=int, default=40)
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--model", default="qwen/qwen3.5-9b")
    parser.add_argument("--llm-url", default="http://127.0.0.1:1234/v1")
    parser.add_argument("--holdout-seed", type=int, default=20260926)
    args = parser.parse_args()

    transfers = run.read_csv(args.data / "transfers.csv")
    feats = features(transfers)
    base_result, rows = run.evaluate(args.jar, args.data, args.threshold)
    mismatches = sum(1 for r in rows if baseline_score(feats[str(r["seq"])]) != r["score"])
    if mismatches:
        raise SystemExit(f"the Python features disagree with the service on {mismatches} postings; fix before asking a model")
    print(f"evidence parity: Python recomputes all {len(rows)} Java scores exactly")

    accepted_rules = []
    current, current_rows = summary(base_result), rows
    log = {"model": args.model, "threshold": args.threshold, "dataset_seed": json.loads(
        (args.data / "meta.json").read_text(encoding="utf-8"))["seed"], "evidence_parity_postings": len(rows),
        "baseline": current, "proposals": []}
    for round_no in range(1, args.rounds + 1):
        history = "\n".join(f"- {p['rule']} -> {p['verdict']} ({'; '.join(p['why']) or 'improved'})"
                            for p in log["proposals"]) or "- none yet"
        pack = evidence([{"seq": r["seq"], "pattern": r["pattern"], "score": r["score"]} for r in current_rows],
                        feats, args.threshold)
        prompt = PROMPT.format(features="\n".join(f"- {k}: {v}" for k, v in FEATURES.items()),
                               evidence=json.dumps(pack, indent=1), history=history)
        entry = {"round": round_no, "rules_in_force": [r["name"] for r in accepted_rules]}
        try:
            raw = ask_with_retry(args.model, prompt, args.llm_url, entry)
            entry["raw_answer"] = raw[-2000:]
            rule, entry["reason"] = extract(raw)
            entry["rule"] = rule
            result, new_rows = evaluate_with(args.jar, args.data, args.threshold, accepted_rules + [rule])
            after = summary(result)
            verdict, why = gate(current, after)
            entry.update({"before": current, "after": after, "verdict": verdict, "why": why})
        except Exception as e:  # a malformed or unloadable rule is a rejection, and it is logged like one
            entry.update({"verdict": "rejected", "why": [f"{type(e).__name__}: {e}"]})
            entry.setdefault("rule", None)
        log["proposals"].append(entry)
        print(f"round {round_no}: {entry.get('rule')} -> {entry['verdict']} {entry['why']}")
        if entry["verdict"] == "accepted":
            accepted_rules.append(entry["rule"])
            current, current_rows = entry["after"], new_rows

    # The holdout played no part in the proposals or the gate: a fresh dataset from another seed.
    if accepted_rules:
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run([sys.executable, str(ROOT / "data" / "fraud" / "generate.py"), "--seed",
                            str(args.holdout_seed), "--out", tmp], check=True, capture_output=True)
            hold_base, _ = run.evaluate(args.jar, Path(tmp), args.threshold)
            hold_new, _ = evaluate_with(args.jar, Path(tmp), args.threshold, accepted_rules)
            log["holdout"] = {"seed": args.holdout_seed, "baseline": summary(hold_base),
                              "with_accepted_rules": summary(hold_new)}
    log["accepted_rules"] = accepted_rules
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "reflection.json").write_text(json.dumps(log, indent=2) + "\n", encoding="utf-8", newline="\n")
    (args.out / "accepted-rules.json").write_text(json.dumps(accepted_rules, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({k: log[k] for k in ("baseline", "accepted_rules")} | ({"holdout": log["holdout"]} if "holdout" in log else {}), indent=2))


if __name__ == "__main__":
    main()
