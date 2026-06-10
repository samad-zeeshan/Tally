"""
Verify that every fraud score used only postings committed before the posting it scored.

Two checks after KONTOGRAPH (arXiv 2608.22389): the Python features recomputed from the stream must equal
what the Java scorer recorded, and perturbing everything after a posting must not change its features.
"""

import random
from dataclasses import replace
from datetime import timedelta

import features as F


def perturb_future(stream, i, rng):
    """Everything after position i rewritten: amounts, times, payers, payees and scores. Ids stay in order."""
    accounts = sorted({p.src for p in stream} | {p.dst for p in stream})
    head = stream[:i + 1]
    tail = []
    for p in stream[i + 1:]:
        src, dst = rng.sample(accounts, 2)
        # Times move up to a day either way, so a feature that windows on event time alone, and not on
        # commit order, pulls these in and gets caught.
        tail.append(replace(p, src=src, dst=dst, amount=rng.randint(1, 5_000_000),
                            at=p.at + timedelta(minutes=rng.randint(-1440, 1440)), score=rng.randint(0, 100)))
    return head + tail


def verify(stream, recorded=None, evidence=None, samples=200, seed=20260925, feature_fn=F.features_at):
    """stream: Postings in id order. recorded: pid -> feature dict from the Java explanation, if any."""
    index = F.Index(stream)
    computed = [feature_fn(index, i) for i in range(len(stream))]
    mismatches = []
    if recorded is not None:
        for i, p in enumerate(stream):
            got = recorded.get(p.pid)
            if got is None:
                mismatches.append({"posting": p.pid, "feature": "*", "why": "no recorded explanation"})
                continue
            for name in F.NAMES:
                if got.get(name) != computed[i][name]:
                    mismatches.append({"posting": p.pid, "feature": name, "java": got.get(name), "python": computed[i][name]})
    late_evidence = []
    for pid, ids in (evidence or {}).items():
        late_evidence += [{"posting": pid, "evidence": e} for e in ids if e >= pid]
    rng = random.Random(seed)
    picked = sorted(rng.sample(range(len(stream)), min(samples, len(stream))))
    leaks = []
    for i in picked:
        moved = perturb_future(stream, i, rng)
        after = feature_fn(F.Index(moved), i)
        for name in F.NAMES:
            if after[name] != computed[i][name]:
                leaks.append({"posting": stream[i].pid, "feature": name, "before": computed[i][name], "after": after[name]})
    return {
        "postings_checked": len(stream),
        "features_per_posting": len(F.NAMES),
        "java_python_mismatches": len(mismatches),
        "late_evidence": len(late_evidence),
        "future_perturbation_samples": len(picked),
        "future_perturbation_leaks": len(leaks),
        "passed": not mismatches and not leaks and not late_evidence,
        "first_problems": (mismatches + late_evidence + leaks)[:10],
    }
