"""
Measure Tally's latency on kind at fixed offered loads, for transfers, statements and reconciliation.

Open loop: requests go out on a schedule whether or not earlier ones finished, and latency counts from
the scheduled time, so a saturated server shows up as queueing instead of hiding it. Writes results.json.
"""

import argparse
import json
import platform
import queue
import random
import subprocess
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cluster  # noqa: E402

OUT = Path(__file__).resolve().parent
# The budgets the table is judged against. A transfer or a statement page is one interactive request,
# while reconciliation scans the whole book and is an operator's call, so it gets more room.
BUDGET_MS = {"transfer": 100, "statement": 100, "reconciliation": 1000}
RATES = {
    "transfer": [25, 50, 100, 200, 300, 400, 600, 800],
    "statement": [25, 50, 100, 200, 400, 800],
    "reconciliation": [1, 2, 5, 10, 20],
}


def percentile(sorted_values, q):
    if not sorted_values:
        return None
    return sorted_values[min(len(sorted_values) - 1, int(round(q * (len(sorted_values) - 1))))]


class Step:
    def __init__(self, api, kind, rate, seconds, accounts, rng):
        self.api, self.kind, self.rate, self.seconds = api, kind, rate, seconds
        self.accounts, self.rng = accounts, rng
        self.latencies, self.errors = [], 0
        self.lock = threading.Lock()
        self.counter = 0

    def request(self):
        if self.kind == "transfer":
            with self.lock:
                self.counter += 1
                n = self.counter
            src, dst = self.rng.sample(self.accounts, 2)
            key = f"load-{self.rate}-{int(time.time())}-{n:07d}"
            return self.api.call("POST", "/transfers", {"fromAccountId": src, "toAccountId": dst, "amountMinor": 1},
                                 {"Idempotency-Key": key})
        if self.kind == "statement":
            return self.api.call("GET", f"/accounts/{self.rng.choice(self.accounts)}/statement?limit=50")
        return self.api.call("GET", "/reconciliation")

    def worker(self, jobs):
        while True:
            scheduled = jobs.get()
            if scheduled is None:
                return
            delay = scheduled - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            try:
                status = self.request()[0]
                ok = status in (200, 201)
            except Exception:
                ok = False
            elapsed = (time.perf_counter() - scheduled) * 1000
            with self.lock:
                if ok:
                    self.latencies.append(elapsed)
                else:
                    self.errors += 1

    def run(self, threads, warmup):
        jobs = queue.Queue()
        pool = [threading.Thread(target=self.worker, args=(jobs,), daemon=True) for _ in range(threads)]
        for t in pool:
            t.start()
        start = time.perf_counter() + 0.2
        total = int(self.rate * (self.seconds + warmup))
        for i in range(total):
            jobs.put(start + i / self.rate)
        for _ in pool:
            jobs.put(None)
        for t in pool:
            t.join()
        wall = time.perf_counter() - start
        # The warmup share of requests is dropped from the percentiles, not from the error count.
        skip = int(self.rate * warmup)
        lat = sorted(self.latencies[skip:]) if len(self.latencies) > skip else sorted(self.latencies)
        achieved = len(self.latencies) / wall if wall > 0 else 0
        return {
            "offered_rps": self.rate,
            "achieved_rps": round(achieved, 1),
            "requests": total,
            "errors": self.errors,
            "p50_ms": round(percentile(lat, 0.50), 1) if lat else None,
            "p95_ms": round(percentile(lat, 0.95), 1) if lat else None,
            "p99_ms": round(percentile(lat, 0.99), 1) if lat else None,
            "max_ms": round(lat[-1], 1) if lat else None,
        }


def sustained(steps, budget):
    """Highest offered rate that kept up, had no errors, and stayed inside the p99 budget."""
    best = None
    for s in steps:
        if s["errors"] == 0 and s["achieved_rps"] >= 0.95 * s["offered_rps"] and s["p99_ms"] is not None and s["p99_ms"] <= budget:
            best = s["offered_rps"]
    return best


def main():
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--seconds", type=float, default=20)
    parser.add_argument("--warmup", type=float, default=3)
    parser.add_argument("--threads", type=int, default=64)
    parser.add_argument("--accounts", type=int, default=50)
    parser.add_argument("--kinds", nargs="*", default=list(RATES))
    parser.add_argument("--where", default="kind on Docker Desktop, one node")
    args = parser.parse_args()

    api = cluster.Api(args.base, cluster.env_value("TALLY_API_TOKEN"), timeout=10)
    # Run straight after the fault gate, the database may still be coming back from the last kill.
    if not cluster.wait_until(lambda: api.call("GET", "/reconciliation")[0] == 200, 180, 2):
        raise SystemExit(f"Tally at {args.base} is not serving reconciliation")
    rng = random.Random(20260925)
    accounts = []
    for i in range(args.accounts):
        status, _, text = api.call("POST", "/accounts", {"name": f"Load {i}", "openingBalanceMinor": 10_000_000})
        if status != 201:
            raise SystemExit(f"could not create an account: {status} {text}")
        accounts.append(json.loads(text)["id"])

    results = {}
    for kind in args.kinds:
        steps = []
        for rate in RATES[kind]:
            step = Step(api, kind, rate, args.seconds, accounts, rng).run(args.threads, args.warmup)
            postings = int(cluster.psql("SELECT count(*) FROM postings")[0][0])
            step["postings_in_book"] = postings
            steps.append(step)
            print(kind, json.dumps(step), flush=True)
            # Past the budget by a wide margin, a higher rate only measures the queue.
            if step["p99_ms"] is None or step["p99_ms"] > 10 * BUDGET_MS[kind] or step["errors"] > step["requests"] * 0.05:
                break
        results[kind] = {"p99_budget_ms": BUDGET_MS[kind], "max_sustained_rps": sustained(steps, BUDGET_MS[kind]),
                         "steps": steps}

    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=cluster.ROOT, capture_output=True, text=True).stdout.strip()
    out = {
        "where": args.where,
        "client": f"Python {platform.python_version()} open-loop driver, {args.threads} threads, on the same machine",
        "api": "2 replicas, 1 CPU limit each; Postgres 500m CPU limit (deploy/k8s/overlays/faults)",
        "code_version": commit,
        "seconds_per_step": args.seconds,
        "warmup_seconds": args.warmup,
        "results": results,
    }
    (OUT / "results.json").write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
