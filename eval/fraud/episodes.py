"""
Episodic memory for the reflection step: matured scoring errors and past proposals, retrieved by similarity.

After arXiv 2609.28771: each episode keeps the state it was decided in and how it turned out. Kept in a
JSON file beside the evaluation and read only offline, never by the service.
"""

import json
import math
from datetime import datetime, timedelta
from pathlib import Path

import features as F

FEATURES = F.NAMES
FLAG = 40


def _vector(features):
    # log1p so an amount in the millions does not drown a count of three.
    return [math.log1p(max(0, features.get(n, 0))) for n in FEATURES]


def _distance(a, b):
    return math.sqrt(sum((x - y) ** 2 for x, y in zip(a, b)))


class Memory:
    def __init__(self, episodes=None, proposals=None):
        self.episodes = list(episodes or [])
        self.proposals = list(proposals or [])

    @classmethod
    def build(cls, rows, dataset_end, maturity=timedelta(days=3), run_id="run", ruleset="v2"):
        """Errors from one evaluation whose label had matured by the end of the data. Later ones wait."""
        episodes = []
        for r in rows:
            at = F.parse_time(r["at"])
            if at > dataset_end - maturity:
                continue
            fraud = r["pattern"] != "normal"
            flagged = r["score"] >= FLAG
            if fraud == flagged:
                continue
            episodes.append({
                "id": f"{run_id}:{r['posting_id']}", "run": run_id, "ruleset": ruleset,
                "posting_id": r["posting_id"], "kind": "missed" if fraud else "false_alert",
                "pattern": r["pattern"], "score": r["score"], "rules": r.get("rules", []),
                "features": {n: r["explanation"]["features"].get(n, 0) for n in FEATURES},
            })
        return cls(episodes)

    def counts(self):
        out = {}
        for e in self.episodes:
            out[e["kind"]] = out.get(e["kind"], 0) + 1
        return out

    def retrieve(self, k=6):
        """For each kind, the k episodes nearest the middle of the missed fraud, where a new rule has to reach."""
        missed = [e for e in self.episodes if e["kind"] == "missed"]
        if not missed:
            return {"missed": [], "false_alert": []}
        vecs = [_vector(e["features"]) for e in missed]
        centre = [sum(col) / len(col) for col in zip(*vecs)]
        out = {}
        for kind in ("missed", "false_alert"):
            pool = [e for e in self.episodes if e["kind"] == kind]
            pool.sort(key=lambda e: (_distance(_vector(e["features"]), centre), e["posting_id"]))
            out[kind] = pool[:k]
        return out

    def remember_proposal(self, record):
        self.proposals.append(record)

    def save(self, path):
        Path(path).write_text(json.dumps({"episodes": self.episodes, "proposals": self.proposals}, indent=1) + "\n",
                              encoding="utf-8", newline="\n")

    @classmethod
    def load(cls, path):
        p = Path(path)
        if not p.exists():
            return cls()
        data = json.loads(p.read_text(encoding="utf-8"))
        return cls(data.get("episodes"), data.get("proposals"))

    def merge(self, other):
        seen = {e["id"] for e in self.episodes}
        self.episodes += [e for e in other.episodes if e["id"] not in seen]


def dataset_end(meta):
    return datetime.strptime(meta["start"], "%Y-%m-%dT%H:%M:%SZ") + timedelta(days=meta["days"])
