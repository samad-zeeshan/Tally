"""
Record the demo's runs against the real jar, so the static site replays real responses and invents nothing.

Writes site/data/ledger-run.json (transfer, dropped reply and retry, reconcile) and site/data/mule-run.json.
"""

import json
import secrets
import socket
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "eval"))
import cluster  # noqa: E402

OUT = ROOT / "site" / "data"


def start(jar):
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    token = secrets.token_hex(24)
    env = {**__import__("os").environ, "TALLY_API_TOKEN": token, "TALLY_PORT": str(port),
           "TALLY_FRAUD_REPLAY_CLOCK": "true", "TALLY_RATE_LIMIT_PER_MINUTE": "100000", "TALLY_LOG_LEVEL": "WARNING"}
    env.pop("TALLY_DB_URL", None)
    proc = subprocess.Popen(["java", "-jar", str(jar)], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    api = cluster.Api(f"http://127.0.0.1:{port}", token, timeout=10)
    if not cluster.wait_until(lambda: api.call("GET", "/health")[0] == 200, 30, 0.2):
        proc.kill()
        raise SystemExit("the jar did not start; run ./mvnw package first")
    return proc, api


class Tape:
    """Every exchange as it happened. The token is never written down."""

    def __init__(self, api):
        self.api = api
        self.steps = []

    def call(self, label, method, path, body=None, headers=None):
        status, h, text = self.api.call(method, path, body, headers)
        keep = {k: v for k, v in h.items() if k in ("idempotency-replayed", "x-request-id")}
        self.steps.append({"label": label, "request": {"method": method, "path": path, "headers": headers or {}, "body": body},
                           "response": {"status": status, "headers": keep, "body": json.loads(text) if text else None}})
        return status, h, json.loads(text) if text else None

    def note(self, label, what):
        self.steps.append({"label": label, "note": what})


def ledger_run(api):
    tape = Tape(api)
    _, _, amara = tape.call("open Amara", "POST", "/accounts", {"name": "Amara", "openingBalanceMinor": 50_000})
    _, _, ben = tape.call("open Ben", "POST", "/accounts", {"name": "Ben", "openingBalanceMinor": 10_000})
    body = {"fromAccountId": amara["id"], "toAccountId": ben["id"], "amountMinor": 12_500}
    tape.call("transfer", "POST", "/transfers", body, {"Idempotency-Key": "demo-transfer-0001"})
    tape.call("balances after the transfer", "GET", f"/accounts/{amara['id']}")
    # The dropped reply: the request goes out and the client hangs up before reading. The server still
    # commits, and the retry with the same key has to find that out.
    lost = {"fromAccountId": amara["id"], "toAccountId": ben["id"], "amountMinor": 7_500}
    api.send_and_hang_up("POST", "/transfers", lost, {"Idempotency-Key": "demo-retry-0002"})
    time.sleep(0.3)
    tape.note("reply lost", "POST /transfers with Idempotency-Key demo-retry-0002 was sent and the connection "
                            "closed before the reply was read")
    tape.call("retry with the same key", "POST", "/transfers", lost, {"Idempotency-Key": "demo-retry-0002"})
    tape.call("Amara after the retry", "GET", f"/accounts/{amara['id']}")
    tape.call("Ben after the retry", "GET", f"/accounts/{ben['id']}")
    tape.call("reconcile", "GET", "/reconciliation")
    return tape.steps


def mule_run(api):
    """A scripted mule chain on the v2 rules: a victim with habits, then money hopping through three mules."""
    tape = Tape(api)
    names = ["Victim", "Shop", "Grocer", "Mule one", "Mule two", "Mule three", "Exit"]
    ids = {}
    for n in names:
        opening = 3_000_000 if n == "Victim" else (200_000 if n.startswith("Mule") else 0)
        _, _, acct = tape.call(f"open {n}", "POST", "/accounts", {"name": n, "openingBalanceMinor": opening})
        ids[n] = acct["id"]
    t0 = datetime(2026, 8, 20, 12, 0, tzinfo=timezone.utc)

    def pay(label, src, dst, amount, minutes):
        at = datetime.fromtimestamp(t0.timestamp() + minutes * 60, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        return tape.call(label, "POST", "/transfers", {"fromAccountId": ids[src], "toAccountId": ids[dst], "amountMinor": amount},
                         {"Idempotency-Key": f"mule-demo-{len(tape.steps):04d}", "X-Tally-Event-Time": at})

    for day in range(6, 0, -1):
        pay(f"Victim's usual shopping, {day} days before", "Victim", "Shop" if day % 2 else "Grocer", 2_400 + 100 * day, -day * 1440)
    pay("Victim pays Mule one", "Victim", "Mule one", 600_000, 0)
    pay("Mule one forwards", "Mule one", "Mule two", 570_000, 7)
    pay("Mule two forwards", "Mule two", "Mule three", 541_000, 15)
    pay("Mule three pays out", "Mule three", "Exit", 520_000, 22)
    for n in ("Victim", "Mule one", "Mule two", "Mule three"):
        deadline = time.time() + 10
        while time.time() < deadline:
            status, _, body = api.call("GET", f"/accounts/{ids[n]}/risk?limit=5")
            if body and json.loads(body)["scores"]:
                break
            time.sleep(0.1)
        tape.call(f"risk for {n}", "GET", f"/accounts/{ids[n]}/risk?limit=1")
    tape.call("reconcile", "GET", "/reconciliation")
    return tape.steps


def main():
    jar = ROOT / "target" / "tally.jar"
    proc, api = start(jar)
    try:
        ledger = ledger_run(api)
        mule = mule_run(api)
    finally:
        proc.terminate()
        proc.wait(timeout=10)
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    meta = {"recorded_on": time.strftime("%Y-%m-%d"), "code_version": commit,
            "how": "target/tally.jar on the in-memory store, driven by eval/record_demo.py; every response copied as returned"}
    OUT.mkdir(parents=True, exist_ok=True)
    for name, steps in (("ledger-run", ledger), ("mule-run", mule)):
        (OUT / f"{name}.json").write_text(json.dumps({**meta, "steps": steps}, indent=1) + "\n", encoding="utf-8", newline="\n")
    print("recorded", len(ledger), "and", len(mule), "steps")


if __name__ == "__main__":
    main()
