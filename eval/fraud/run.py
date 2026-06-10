"""
Replay the synthetic dataset through a running Tally, pull every score back, and measure the scorer.

Measures the v1 rules and the v2 graph rules from the same jar, draws precision-recall curves, and runs
the point-in-time verifier over every v2 score. Writes eval/fraud/results.json.
"""

import argparse
import csv
import http.client
import json
import os
import secrets
import socket
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
PATTERNS = ["burst", "structuring", "account_takeover", "mule_chain"]


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Api:
    """One keep-alive connection. Sequential on purpose: the queue scores in arrival order."""

    def __init__(self, port, token):
        self.port = port
        self.token = token
        self.conn = http.client.HTTPConnection("127.0.0.1", port, timeout=30)

    def call(self, method, path, body=None, headers=None):
        h = {"Authorization": "Bearer " + self.token}
        if body is not None:
            h["Content-Type"] = "application/json"
        h.update(headers or {})
        data = json.dumps(body) if body is not None else None
        for attempt in range(2):
            try:
                self.conn.request(method, path, body=data, headers=h)
                r = self.conn.getresponse()
                return r.status, r.read().decode("utf-8")
            except (http.client.HTTPException, ConnectionError):
                # The JDK server may close an idle keep-alive socket; one reconnect is enough.
                self.conn.close()
                self.conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)
                if attempt == 1:
                    raise


def start_server(jar, extra_env):
    token = secrets.token_hex(24)
    port = free_port()
    env = dict(os.environ)
    env.pop("TALLY_DB_URL", None)   # the in-memory store: this measures the rules, not Postgres
    env.update({
        "TALLY_API_TOKEN": token,
        "TALLY_PORT": str(port),
        "TALLY_FRAUD_REPLAY_CLOCK": "true",
        "TALLY_RATE_LIMIT_PER_MINUTE": "10000000",
        "TALLY_LOG_LEVEL": "WARNING",
    })
    env.update(extra_env)
    proc = subprocess.Popen(["java", "-jar", str(jar)], env=env,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    api = Api(port, token)
    for _ in range(100):
        try:
            if api.call("GET", "/health")[0] == 200:
                return proc, api
        except OSError:
            pass
        time.sleep(0.1)
    proc.kill()
    raise SystemExit("the server did not start; build it first with ./mvnw package")


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def replay(api, data):
    accounts = read_csv(data / "accounts.csv")
    transfers = read_csv(data / "transfers.csv")
    ids = {}
    for a in accounts:
        status, body = api.call("POST", "/accounts", {"name": a["account"], "openingBalanceMinor": int(a["opening_minor"])})
        if status != 201:
            raise SystemExit(f"could not create {a['account']}: {status} {body}")
        ids[a["account"]] = json.loads(body)["id"]
    applied = {}          # seq -> transfer id
    refused = {}          # seq -> status
    for t in transfers:
        status, body = api.call("POST", "/transfers",
                                {"fromAccountId": ids[t["from"]], "toAccountId": ids[t["to"]], "amountMinor": int(t["amount_minor"])},
                                # Zero-padded: the edge wants 8 to 64 key characters, and eval-1 is six.
                                {"Idempotency-Key": f"eval-{int(t['seq']):07d}", "X-Tally-Event-Time": t["event_time"]})
        if status == 201:
            applied[t["seq"]] = json.loads(body)["id"]
        else:
            refused[t["seq"]] = status
    return ids, transfers, applied, refused


def wait_for_scoring(api, expected, timeout=120):
    deadline = time.time() + timeout
    while time.time() < deadline:
        status, text = api.call("GET", "/metrics")
        seen = sum(int(line.rsplit(" ", 1)[1]) for line in text.splitlines()
                   if line.startswith("tally_fraud_postings_total{"))
        if seen >= expected:
            return text
        time.sleep(0.2)
    raise SystemExit(f"the scorer saw fewer than {expected} postings in {timeout}s")


def pull_scores(api, ids, transfers, applied):
    senders = sorted({t["from"] for t in transfers if t["seq"] in applied})
    by_transfer = {}
    for key in senders:
        status, body = api.call("GET", f"/accounts/{ids[key]}/risk?limit=500")
        scores = json.loads(body)["scores"]
        sent = sum(1 for t in transfers if t["from"] == key and t["seq"] in applied)
        if len(scores) != sent:
            raise SystemExit(f"{key} sent {sent} payments but has {len(scores)} scores")
        for s in scores:
            by_transfer[s["transferId"]] = s
    return by_transfer


def auroc(positives, negatives):
    # The Mann-Whitney form: the chance a random fraud posting outscores a random normal one, ties half.
    if not positives or not negatives:
        return None
    ranked = sorted([(s, 1) for s in positives] + [(s, 0) for s in negatives])
    rank_sum, i = 0.0, 0
    while i < len(ranked):
        j = i
        while j < len(ranked) and ranked[j][0] == ranked[i][0]:
            j += 1
        mean_rank = (i + 1 + j) / 2
        rank_sum += mean_rank * sum(1 for k in range(i, j) if ranked[k][1] == 1)
        i = j
    n_pos, n_neg = len(positives), len(negatives)
    return (rank_sum - n_pos * (n_pos + 1) / 2) / (n_pos * n_neg)


def measure(rows, threshold):
    """rows: dicts with pattern, episode, score, in replay order."""
    normal = [r["score"] for r in rows if r["pattern"] == "normal"]
    false_pos = sum(1 for s in normal if s >= threshold)
    out = {
        "normal": {"postings": len(normal), "flagged": false_pos,
                   "false_positive_rate": round(false_pos / len(normal), 4) if normal else None},
        "patterns": {},
    }

    def block(members):
        scores = [r["score"] for r in members]
        tp = sum(1 for s in scores if s >= threshold)
        episodes = {}
        for r in members:
            episodes.setdefault(r["episode"], []).append(r["score"] >= threshold)
        # Latency: how many fraud postings in an episode went by before the first one was flagged.
        latencies = [flags.index(True) for flags in episodes.values() if True in flags]
        return {
            "postings": len(scores),
            "episodes": len(episodes),
            "flagged": tp,
            "precision": round(tp / (tp + false_pos), 4) if tp + false_pos else None,
            "recall": round(tp / len(scores), 4) if scores else None,
            "auroc": None if auroc(scores, normal) is None else round(auroc(scores, normal), 4),
            "episodes_detected": len(latencies),
            "latency_postings_mean": round(statistics.mean(latencies), 2) if latencies else None,
            "latency_postings_median": statistics.median(latencies) if latencies else None,
        }

    for p in PATTERNS:
        out["patterns"][p] = block([r for r in rows if r["pattern"] == p])
    out["all_fraud"] = block([r for r in rows if r["pattern"] != "normal"])
    return out


def evaluate(jar, data, threshold, extra_env=None):
    labels = {r["seq"]: r for r in read_csv(data / "labels.csv")}
    proc, api = start_server(jar, extra_env or {})
    try:
        started = time.time()
        ids, transfers, applied, refused = replay(api, data)
        wait_for_scoring(api, len(applied))
        scores = pull_scores(api, ids, transfers, applied)
        seconds = round(time.time() - started, 1)
    finally:
        proc.terminate()
        proc.wait(timeout=10)
    rows = []
    for t in transfers:
        if t["seq"] in applied:
            s = scores[applied[t["seq"]]]
            lab = labels[t["seq"]]
            rows.append({"seq": int(t["seq"]), "pattern": lab["pattern"], "episode": lab["episode"],
                         "score": s["score"], "rules": s["rules"], "posting_id": s["postingId"],
                         "from": t["from"], "to": t["to"], "amount": int(t["amount_minor"]), "at": s["eventAt"],
                         "explanation": s.get("explanation", {})})
    result = measure(rows, threshold)
    result["replay"] = {"transfers": len(transfers), "applied": len(applied), "refused": len(refused),
                        "refused_fraud": sum(1 for q in refused if labels[q]["pattern"] != "normal"),
                        "seconds": seconds}
    return result, rows


def rule_counts(rows):
    counts = {}
    for r in rows:
        key = "fraud" if r["pattern"] != "normal" else "normal"
        for rule in r["rules"]:
            counts.setdefault(rule, {"normal": 0, "fraud": 0})[key] += 1
    return dict(sorted(counts.items()))


def pr_curve(rows, pattern=None):
    """Precision and recall at every whole-number flag line from 0 to 100."""
    normal = [r["score"] for r in rows if r["pattern"] == "normal"]
    fraud = [r["score"] for r in rows if r["pattern"] != "normal" and (pattern is None or r["pattern"] == pattern)]
    curve = []
    for t in range(0, 101):
        tp = sum(1 for s in fraud if s >= t)
        fp = sum(1 for s in normal if s >= t)
        curve.append({"threshold": t, "precision": round(tp / (tp + fp), 4) if tp + fp else None,
                      "recall": round(tp / len(fraud), 4) if fraud else None, "flagged": tp + fp})
    return curve


def precision_at_recall(curve, recall):
    """The best precision among flag lines that still reach the given recall, and the line that gives it."""
    ok = [c for c in curve if c["recall"] is not None and c["recall"] >= recall and c["precision"] is not None]
    if not ok:
        return {"precision": None, "threshold": None, "recall": None}
    best = max(ok, key=lambda c: (c["precision"], c["threshold"]))
    return {"precision": best["precision"], "threshold": best["threshold"], "recall": best["recall"]}


def alerts_per_thousand(rows, threshold):
    return round(1000 * sum(1 for r in rows if r["score"] >= threshold) / len(rows), 2)


def summarize(result, rows, threshold):
    return {
        **{k: result[k] for k in ("normal", "patterns", "all_fraud", "replay")},
        "alerts_per_1000_postings": alerts_per_thousand(rows, threshold),
        "rules_fired": rule_counts(rows),
    }


def stream_of(rows):
    import features as F
    ordered = sorted(rows, key=lambda r: r["posting_id"])
    return [F.Posting(r["posting_id"], r["from"], r["to"], r["amount"], F.parse_time(r["at"]), r["score"]) for r in ordered]


def explanations(rows, threshold, budget=50_000):
    micros = sorted(r["explanation"].get("micros", 0) for r in rows)
    pick = lambda q: micros[min(len(micros) - 1, int(q * (len(micros) - 1)))]
    flagged = [r for r in rows if r["score"] >= threshold]
    return {
        "budget_micros": budget,
        "p50_micros": pick(0.5), "p99_micros": pick(0.99), "max_micros": micros[-1],
        "over_budget": sum(1 for m in micros if m > budget),
        "flagged": len(flagged),
        "flagged_with_evidence": sum(1 for r in flagged if r["explanation"].get("evidence")),
    }


def run_both(jar, data, threshold):
    v1, v1_rows = evaluate(jar, data, threshold, {"TALLY_FRAUD_RULESET": "v1"})
    v2, v2_rows = evaluate(jar, data, threshold, {"TALLY_FRAUD_RULESET": "v2"})
    return v1, v1_rows, v2, v2_rows


def brief(result, rows, threshold):
    return {"all_fraud": result["all_fraud"], "normal": result["normal"],
            "alerts_per_1000_postings": alerts_per_thousand(rows, threshold),
            "patterns": {p: {k: result["patterns"][p][k] for k in ("precision", "recall", "auroc")} for p in PATTERNS}}


def main():
    import verify_pit
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--jar", type=Path, default=ROOT / "target" / "tally.jar")
    parser.add_argument("--data", type=Path, default=ROOT / "data" / "fraud")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--threshold", type=int, default=40)
    parser.add_argument("--holdout-seed", type=int, default=20260926)
    args = parser.parse_args()

    v1, v1_rows, v2, v2_rows = run_both(args.jar, args.data, args.threshold)
    curves = {"v1": {"all_fraud": pr_curve(v1_rows)}, "v2": {"all_fraud": pr_curve(v2_rows)}}
    for p in PATTERNS:
        curves["v1"][p] = pr_curve(v1_rows, p)
        curves["v2"][p] = pr_curve(v2_rows, p)
    # v1 precision was computed per pattern against every false positive, and so is this, so the two
    # numbers side by side are the same measurement.
    at_v1_recall = {}
    for p in PATTERNS + ["all_fraud"]:
        v1m = v1["patterns"][p] if p in PATTERNS else v1["all_fraud"]
        at_v1_recall[p] = {"v1_recall": v1m["recall"], "v1_precision": v1m["precision"],
                           "v2": precision_at_recall(curves["v2"][p], v1m["recall"])}

    pit = verify_pit.verify(stream_of(v2_rows),
                            recorded={r["posting_id"]: r["explanation"].get("features", {}) for r in v2_rows},
                            evidence={r["posting_id"]: r["explanation"].get("evidence", []) for r in v2_rows})

    # A second dataset from another seed. The v2 rules were chosen looking at the first one, so this is
    # the number that says whether they only fit it.
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run([sys.executable, str(ROOT / "data" / "fraud" / "generate.py"), "--seed", str(args.holdout_seed),
                        "--out", tmp], check=True, capture_output=True)
        h1, h1_rows, h2, h2_rows = run_both(args.jar, Path(tmp), args.threshold)
    holdout = {"seed": args.holdout_seed, "v1": brief(h1, h1_rows, args.threshold), "v2": brief(h2, h2_rows, args.threshold)}

    meta = json.loads((args.data / "meta.json").read_text(encoding="utf-8"))
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    out = {
        "dataset": {"seed": meta["seed"], "transfers": meta["transfers"], "accounts": meta["accounts"]},
        "threshold": args.threshold,
        "store": "in-memory (TALLY_DB_URL unset), replay clock on",
        "code_version": commit,
        "v1": summarize(v1, v1_rows, args.threshold),
        "v2": summarize(v2, v2_rows, args.threshold),
        "precision_at_v1_recall": at_v1_recall,
        "explanations": explanations(v2_rows, args.threshold),
        "point_in_time": pit,
        "holdout": holdout,
    }
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "results.json").write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8", newline="\n")
    (args.out / "pr-curves.json").write_text(json.dumps(curves) + "\n", encoding="utf-8", newline="\n")
    # Kept for the reflection step and the demo: every v2 score with its explanation, one per line.
    with open(args.out / "scores-v2.jsonl", "w", encoding="utf-8", newline="\n") as f:
        for r in v2_rows:
            f.write(json.dumps(r, separators=(",", ":")) + "\n")
    for name in ("v1", "v2"):
        a = out[name]["all_fraud"]
        print(f"{name}: precision {a['precision']} recall {a['recall']} auroc {a['auroc']}, "
              f"false positives {out[name]['normal']['flagged']}, alerts per 1000 {out[name]['alerts_per_1000_postings']}")
    print("point in time:", {k: pit[k] for k in ("postings_checked", "java_python_mismatches", "future_perturbation_leaks", "passed")})
    print("holdout:", json.dumps({k: holdout[k]["all_fraud"] for k in ("v1", "v2")}))
    sys.exit(0 if pit["passed"] else 1)


if __name__ == "__main__":
    main()
