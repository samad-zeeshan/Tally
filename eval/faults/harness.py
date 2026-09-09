"""
Drive concurrent transfers at Tally on kind while injecting faults, then check the books.

Jepsen style: each client retries one key until it gets a definite answer, and the database is then
checked against what the clients were told. Writes eval/faults/results.json and one timeline per fault.
"""

import argparse
import json
import random
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import cluster  # noqa: E402

OUT = Path(__file__).resolve().parent
FAULTS = ["none", "api_pod_kill", "postgres_restart", "partition", "clock_skew", "dropped_responses", "mixed"]

PARTITION_POLICY = """
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: faults-partition
  namespace: tally
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: tally-db
  policyTypes: [Ingress]
  ingress: []
"""


class Run:
    """One fault run: the clients, the fault thread, and every attempt they made."""

    def __init__(self, api, fault, run_id, accounts, workers, min_seconds, pace, seed):
        self.api = api
        self.fault = fault
        self.run_id = run_id
        self.accounts = accounts
        self.workers = workers
        self.min_seconds = min_seconds
        self.pace = pace
        self.rng = random.Random(seed)
        self.lock = threading.Lock()
        self.events = []          # (t, kind, detail) for the timeline and the demo
        self.attempts = []        # (t, key, outcome)
        self.resolved = {}        # key -> "applied" | "insufficient_funds" | "unresolved" | a violation code
        self.bodies = {}
        self.started = None
        self.stop_fault = threading.Event()
        self.fault_done = threading.Event()
        self.fault_done_at = None

    # Clients keep sending new transfers until the whole fault schedule has played out and a few quiet
    # seconds after it, so every fault lands on live traffic however fast the service answers.
    def finished(self):
        return (self.fault_done.is_set() and time.time() - self.started >= self.min_seconds
                and time.time() >= self.fault_done_at + 5)

    def now(self):
        return round(time.time() - self.started, 3)

    def event(self, kind, detail=""):
        with self.lock:
            self.events.append((self.now(), kind, detail))
        print(f"  {self.now():7.2f}s {kind} {detail}", flush=True)

    # A dropped response is the client hanging up before reading. The server still commits, and the
    # retry has to find out what happened through the key alone.
    def attempt(self, key, body, headers, rng):
        drop = self.fault in ("dropped_responses", "mixed") and rng.random() < 0.3
        try:
            if drop:
                self.api.send_and_hang_up("POST", "/transfers", body, headers)
                return "dropped"
            status, h, text = self.api.call("POST", "/transfers", body, headers)
        except Exception as e:  # any transport failure is an unknown outcome, never a failure
            return "error:" + type(e).__name__
        replayed = h.get("idempotency-replayed") == "true"
        if status == 201:
            return "replayed_applied" if replayed else "applied"
        if status == 422 and '"INSUFFICIENT_FUNDS"' in text:
            return "replayed_insufficient" if replayed else "insufficient_funds"
        if status == 409:
            return "key_conflict"
        return f"http_{status}"

    def client(self, worker, deadline_s):
        rng = random.Random(f"{self.run_id}-{worker}")
        clock = datetime(2026, 9, 1, tzinfo=timezone.utc)
        i = -1
        while not self.finished():
            i += 1
            src, dst = rng.sample(self.accounts, 2)
            key = f"f-{self.run_id}-{worker:02d}-{i:04d}"
            body = {"fromAccountId": src, "toAccountId": dst, "amountMinor": rng.randint(1, 5_000)}
            self.bodies[key] = body
            headers = {"Idempotency-Key": key}
            if self.fault in ("clock_skew", "mixed"):
                # The replay clock trusts this header. Jumping it hours back and forth is the skew.
                clock += timedelta(hours=rng.choice([-6, -1, 0, 1, 6]), seconds=rng.randint(0, 59))
                headers["X-Tally-Event-Time"] = clock.strftime("%Y-%m-%dT%H:%M:%SZ")
            give_up = time.time() + deadline_s
            outcome = "unresolved"
            while time.time() < give_up:
                result = self.attempt(key, body, headers, rng)
                with self.lock:
                    self.attempts.append((self.now(), key, result))
                if result in ("applied", "replayed_applied"):
                    outcome = "applied"
                    break
                if result in ("insufficient_funds", "replayed_insufficient"):
                    outcome = "insufficient_funds"
                    break
                if result == "key_conflict":
                    outcome = "key_conflict"
                    break
                time.sleep(0.05 if result == "dropped" else rng.uniform(0.2, 0.8))
            with self.lock:
                self.resolved[key] = outcome
            time.sleep(self.pace)


def api_pods():
    out = cluster.kubectl("get", "pods", "-l", "app.kubernetes.io/name=tally-api", "-o",
                          "jsonpath={range .items[*]}{.metadata.name}{\"\\n\"}{end}")
    return [p for p in out.split() if p]


def kill_api_pod(run, rng):
    pods = api_pods()
    if pods:
        victim = rng.choice(pods)
        cluster.kubectl("delete", "pod", victim, "--grace-period=0", "--force", "--wait=false", check=False)
        run.event("fault", f"killed api pod {victim}")


def restart_postgres(run):
    cluster.kubectl("delete", "pod", "tally-db-0", "--grace-period=0", "--force", "--wait=false", check=False)
    run.event("fault", "killed postgres pod tally-db-0")
    ok = cluster.wait_until(lambda: cluster.psql("SELECT 1") == [["1"]], 180, 2)
    run.event("heal", "postgres answering again" if ok else "postgres did not come back in 180s")


def partition(run, seconds):
    cluster.kubectl("apply", "-f", "-", input_text=PARTITION_POLICY)
    # A network policy only judges new connections, so the pool's open sockets are cut from the database
    # side as well. Otherwise the partition would only bite once a connection happened to close.
    cluster.psql("SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity "
                 "WHERE datname = 'tally' AND pid <> pg_backend_pid() AND backend_type = 'client backend'")
    run.event("fault", f"partitioned the api from postgres for {seconds}s")
    run.stop_fault.wait(seconds)
    cluster.kubectl("delete", "networkpolicy", "faults-partition", "--ignore-not-found")
    run.event("heal", "partition removed")


def fault_thread(run, warmup):
    try:
        schedule(run, warmup)
    finally:
        run.fault_done_at = time.time()
        run.fault_done.set()


def schedule(run, warmup):
    rng = random.Random(run.run_id)
    if run.stop_fault.wait(warmup):
        return
    f = run.fault
    if f == "api_pod_kill":
        for _ in range(3):
            kill_api_pod(run, rng)
            if run.stop_fault.wait(8):
                return
    elif f == "postgres_restart":
        restart_postgres(run)
    elif f == "partition":
        partition(run, 15)
    elif f == "mixed":
        kill_api_pod(run, rng)
        if run.stop_fault.wait(6):
            return
        partition(run, 10)
        if run.stop_fault.wait(6):
            return
        restart_postgres(run)
    elif f in ("clock_skew", "dropped_responses"):
        run.event("fault", "active for the whole run, on the client side")


def create_accounts(api, run_id, n, opening):
    # Created before any fault. Account creation has no idempotency key, so a lost reply here could
    # leave an orphan account, which would muddy the balance check.
    ids = {}
    for i in range(n):
        status, _, text = api.call("POST", "/accounts", {"name": f"Fault {run_id} {i}", "openingBalanceMinor": opening})
        if status != 201:
            raise SystemExit(f"could not create an account: {status} {text}")
        ids[json.loads(text)["id"]] = opening
    return ids


def check(run, openings):
    """Every check runs against Postgres directly, and against the API's own reconciliation."""
    keys = sorted(run.resolved)
    # Fresh connections, so the Service spreads them over every API pod. One reused keep-alive connection
    # only ever reached the healthy pod and hid a pod whose pool had emptied for good.
    def every_pod_serves():
        for _ in range(12):
            if run.api.fresh_call("GET", "/reconciliation")[0] != 200:
                return False
        return True
    started = time.time()
    recovered = cluster.wait_until(every_pod_serves, 120, 2)
    recovery_seconds = round(time.time() - started, 1) if recovered else None
    # Scoring runs after commit on its own queue, so give it a moment to drain before counting scores.
    time.sleep(5)
    prefix = f"f-{run.run_id}-"
    ok_api = cluster.wait_until(lambda: run.api.call("GET", "/reconciliation")[0] == 200, 180, 2)
    recon = json.loads(run.api.call("GET", "/reconciliation")[2]) if ok_api else {}
    total = int(cluster.psql("SELECT COALESCE(SUM(amount_minor), 0) FROM postings")[0][0])
    balances_sum = int(cluster.psql("SELECT COALESCE(SUM(balance_minor), 0) FROM accounts")[0][0])
    negatives = int(cluster.psql("SELECT count(*) FROM accounts WHERE balance_minor < 0 AND NOT allow_negative")[0][0])
    rows = cluster.psql(
        "SELECT t.idempotency_key, t.status, count(p.id) FROM transfers t LEFT JOIN postings p ON p.transfer_id = t.id "
        f"WHERE t.idempotency_key LIKE '{prefix}%' GROUP BY t.idempotency_key, t.status")
    db = {k: (status, int(n)) for k, status, n in rows}
    id_list = ",".join(f"'{a}'" for a in openings)
    stored = {a: int(b) for a, b in cluster.psql(f"SELECT id, balance_minor FROM accounts WHERE id IN ({id_list})")}
    foreign = int(cluster.psql(
        f"SELECT count(*) FROM transfers WHERE (from_account_id IN ({id_list}) OR to_account_id IN ({id_list})) "
        f"AND idempotency_key NOT LIKE '{prefix}%' AND idempotency_key NOT LIKE 'open:%'")[0][0])
    scored = cluster.psql(
        "SELECT count(*), count(DISTINCT s.posting_id) FROM posting_scores s JOIN transfers t ON t.id = s.transfer_id "
        f"WHERE t.idempotency_key LIKE '{prefix}%'")[0]

    expected = dict(openings)
    for k in keys:
        if run.resolved[k] == "applied":
            b = run.bodies[k]
            expected[b["fromAccountId"]] -= b["amountMinor"]
            expected[b["toAccountId"]] += b["amountMinor"]

    double = [k for k, (status, n) in db.items() if n > 2 or (status == "applied" and n != 2)
              or (status == "insufficient_funds" and n != 0)]
    phantom = [k for k in db if k not in run.resolved] + (["foreign"] * foreign)
    lost_ack = [k for k in keys if run.resolved[k] == "applied" and db.get(k, ("", 0))[0] != "applied"]
    mismatch = [k for k in keys if run.resolved[k] in ("applied", "insufficient_funds")
                and db.get(k, ("missing", 0))[0] != run.resolved[k]]
    unresolved = [k for k in keys if run.resolved[k] == "unresolved"]
    conflicts = [k for k in keys if run.resolved[k] == "key_conflict"]
    balance_off = [a for a in openings if stored.get(a) != expected[a]]
    return {
        "every_pod_serving_after_seconds": recovery_seconds,
        "reconciliation_consistent": recon.get("consistent"),
        "reconciliation_global_sum_minor": recon.get("globalSumMinor"),
        "postings_sum_minor": total,
        "balances_sum_minor": balances_sum,
        "negative_balances": negatives,
        "conservation_violations": int(total != 0) + int(balances_sum != 0),
        "double_applications": len(double),
        "phantom_transfers": len(phantom),
        "lost_acknowledged_transfers": len(lost_ack),
        "outcome_mismatches": len(mismatch),
        "balance_mismatches": len(balance_off),
        "unresolved_keys": len(unresolved),
        "key_conflicts": len(conflicts),
        "scores_written": int(scored[0]),
        "postings_scored_twice": int(scored[0]) - int(scored[1]),
    }


def one_run(api, fault, args, seed):
    run_id = f"{fault.replace('_', '')[:4]}{int(time.time()) % 100000:05d}"
    openings = create_accounts(api, run_id, args.accounts, args.opening)
    run = Run(api, fault, run_id, list(openings), args.workers, args.min_seconds, args.pace, seed)
    print(f"== {fault} ({run_id}): {args.workers} clients over {args.accounts} accounts, at least {args.min_seconds}s")
    run.started = time.time()
    run.event("start", fault)
    clients = [threading.Thread(target=run.client, args=(w, args.deadline)) for w in range(args.workers)]
    injector = threading.Thread(target=fault_thread, args=(run, args.warmup))
    for t in clients + [injector]:
        t.start()
    for t in clients:
        t.join()
    run.stop_fault.set()
    injector.join()
    run.event("clients_done", f"{len(run.resolved)} keys")
    result = check(run, openings)
    run.event("checked", "books balance" if result["conservation_violations"] == 0 else "books do not balance")

    outcomes = {}
    for _, _, r in run.attempts:
        label = r.split(":")[0]
        outcomes[label] = outcomes.get(label, 0) + 1
    resolved = {}
    for v in run.resolved.values():
        resolved[v] = resolved.get(v, 0) + 1
    # Availability, beside the correctness checks: the longest stretch in which no client got an answer.
    ok_times = sorted(t for t, _, r in run.attempts if r in ("applied", "replayed_applied", "insufficient_funds",
                                                                "replayed_insufficient"))
    edges = [0.0] + ok_times + [run.attempts[-1][0] if run.attempts else 0.0]
    longest_gap = round(max(b - a for a, b in zip(edges, edges[1:])), 1) if len(edges) > 1 else None
    failed = sum(n for label, n in outcomes.items() if label not in
                 ("applied", "replayed_applied", "insufficient_funds", "replayed_insufficient"))
    # A fault that no client ever noticed tested nothing, so the run does not count as a pass.
    observed = {"none": True, "clock_skew": True, "dropped_responses": outcomes.get("dropped", 0) > 0}.get(fault, failed > 0)
    violations = sum(result[k] for k in ("conservation_violations", "double_applications", "phantom_transfers",
                                          "lost_acknowledged_transfers", "outcome_mismatches", "balance_mismatches",
                                          "negative_balances", "key_conflicts"))
    summary = {
        "fault": fault,
        "run_id": run_id,
        "keys": len(run.resolved),
        "attempts": len(run.attempts),
        "retries": len(run.attempts) - len(run.resolved),
        "attempt_outcomes": dict(sorted(outcomes.items())),
        "resolved": dict(sorted(resolved.items())),
        "seconds": round(time.time() - run.started, 1),
        **result,
        "violations": violations,
        "longest_gap_seconds": longest_gap,
        "fault_observed": observed,
        "passed": (violations == 0 and result["unresolved_keys"] == 0 and result["reconciliation_consistent"] is True
                   and observed and result["every_pod_serving_after_seconds"] is not None),
    }
    per_second = {}
    for t, _, r in run.attempts:
        bucket = per_second.setdefault(int(t), {"ok": 0, "retry": 0})
        bucket["ok" if r in ("applied", "replayed_applied", "insufficient_funds", "replayed_insufficient") else "retry"] += 1
    timeline = {"fault": fault, "run_id": run_id, "events": run.events,
                "per_second": [{"t": t, **v} for t, v in sorted(per_second.items())], "summary": summary}
    return summary, timeline


def main():
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--faults", nargs="*", default=FAULTS, choices=FAULTS)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--min-seconds", type=float, default=30)
    parser.add_argument("--pace", type=float, default=0.1, help="seconds a client waits between transfers")
    parser.add_argument("--accounts", type=int, default=6)
    # Small openings on purpose, so some transfers are refused for funds and that outcome is exercised too.
    parser.add_argument("--opening", type=int, default=40_000)
    parser.add_argument("--warmup", type=float, default=5)
    parser.add_argument("--deadline", type=float, default=240)
    parser.add_argument("--where", default="kind", help="recorded in the results: where the cluster ran")
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args()

    api = cluster.Api(args.base, cluster.env_value("TALLY_API_TOKEN"))
    if not cluster.wait_until(lambda: api.call("GET", "/health")[0] == 200, 60):
        raise SystemExit(f"Tally is not answering at {args.base}")
    runs, all_ok = [], True
    (args.out / "runs").mkdir(parents=True, exist_ok=True)
    for i, fault in enumerate(args.faults):
        summary, timeline = one_run(api, fault, args, seed=i)
        runs.append(summary)
        all_ok &= summary["passed"]
        (args.out / "runs" / f"{fault}.json").write_text(json.dumps(timeline, indent=1) + "\n", encoding="utf-8", newline="\n")
        print(json.dumps({k: summary[k] for k in ("fault", "keys", "retries", "violations", "unresolved_keys", "passed")}))
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=cluster.ROOT, capture_output=True, text=True).stdout.strip()
    result = {
        "where": args.where,
        "code_version": commit,
        "clients": args.workers,
        "min_seconds_per_run": args.min_seconds,
        "client_pace_seconds": args.pace,
        "accounts_per_run": args.accounts,
        "runs": runs,
        "totals": {k: sum(r[k] for r in runs) for k in ("keys", "attempts", "retries", "violations", "unresolved_keys",
                                                         "double_applications", "phantom_transfers", "conservation_violations")},
        "passed": all_ok,
    }
    (args.out / "results.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8", newline="\n")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
