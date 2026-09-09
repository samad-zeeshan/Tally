"""
Talk to Tally on the kind cluster: the HTTP API through the NodePort, and Postgres through kubectl exec.

Shared by the fault harness and the load driver. Standard library only.
"""

import http.client
import json
import os
import subprocess
import threading
import time
from pathlib import Path
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
NS = "tally"


def env_value(name, path=ROOT / ".env"):
    # grep, not source, the same rule the shell scripts follow, so a stray line in .env is never run.
    if os.environ.get(name):
        return os.environ[name]
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"{name} is not set and not in {path}")


class Api:
    """One keep-alive connection per thread. A failed call closes it so the next call reconnects."""

    def __init__(self, base, token, timeout=3.0):
        u = urlparse(base)
        self.host, self.port = u.hostname, u.port or 80
        self.token = token
        self.timeout = timeout
        self.local = threading.local()

    def _conn(self):
        c = getattr(self.local, "conn", None)
        if c is None:
            c = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
            self.local.conn = c
        return c

    def reset(self):
        c = getattr(self.local, "conn", None)
        if c is not None:
            c.close()
        self.local.conn = None

    def headers(self, extra=None, body=False):
        h = {"Authorization": "Bearer " + self.token}
        if body:
            h["Content-Type"] = "application/json"
        h.update(extra or {})
        return h

    def call(self, method, path, body=None, headers=None):
        """Returns (status, headers, text). Raises OSError or HTTPException on any transport failure."""
        data = json.dumps(body) if body is not None else None
        c = self._conn()
        try:
            c.request(method, path, body=data, headers=self.headers(headers, body is not None))
            r = c.getresponse()
            return r.status, {k.lower(): v for k, v in r.getheaders()}, r.read().decode("utf-8")
        except Exception:
            self.reset()
            raise

    def fresh_call(self, method, path):
        """One request on its own connection, so a load balancer may send it to any backend."""
        c = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        try:
            c.request(method, path, headers=self.headers())
            r = c.getresponse()
            return r.status, r.read()
        except Exception:
            return 0, b""
        finally:
            c.close()

    def send_and_hang_up(self, method, path, body, headers=None):
        """Sends a request and closes the socket without reading the answer: a response lost on the way back."""
        c = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        try:
            c.request(method, path, body=json.dumps(body), headers=self.headers(headers, True))
        finally:
            c.close()


def kubectl(*args, check=True, timeout=120, input_text=None):
    proc = subprocess.run(["kubectl", "-n", NS, *args], capture_output=True, text=True, timeout=timeout,
                          input=input_text)
    if check and proc.returncode != 0:
        raise RuntimeError(f"kubectl {' '.join(args)} failed: {proc.stderr.strip()[:300]}")
    return proc.stdout


def psql(sql, timeout=60):
    """Rows as lists of strings. Runs inside the database pod, so it works while the API is cut off."""
    out = kubectl("exec", "tally-db-0", "-c", "postgres", "--", "psql", "-U", "tally", "-d", "tally",
                  "-At", "-F", "\t", "-v", "ON_ERROR_STOP=1", "-c", sql, timeout=timeout)
    return [line.split("\t") for line in out.splitlines() if line]


def wait_until(predicate, timeout, interval=1.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if predicate():
                return True
        except Exception:
            pass
        time.sleep(interval)
    return False
