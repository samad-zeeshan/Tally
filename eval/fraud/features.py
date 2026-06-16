"""
The scorer's features recomputed in Python from the posting stream, for the verifier and the reflection step.

A second backend for the same feature definitions as FeatureRule.Feature, in the KONTOGRAPH sense: it
sees the whole stream, so it has every chance to leak, and the verifier checks that it does not.
"""

import bisect
from dataclasses import dataclass
from datetime import datetime, timedelta

WINDOW_LIMIT = 200          # Scorer.WINDOW_LIMIT
PAYERS_CAP = 50             # Scorer.PAYERS_CAP
FLAG = 40                   # Score.FLAG_THRESHOLD
CYCLE_HOPS = 3
TEN_MIN, HOUR, DAY = timedelta(minutes=10), timedelta(hours=1), timedelta(hours=24)

NAMES = ["outgoing_10m", "outgoing_60m", "amount_minor", "amount_to_median_x100", "history_size",
         "counterparty_known", "distinct_payees_60m", "hour_seen_count", "incoming_60m_minor", "passthrough_pct",
         "fan_in_60m", "payee_payers", "payee_outgoing", "flagged_in_60m_minor", "seed_hops", "cycle_24h"]


@dataclass(frozen=True)
class Posting:
    """One scored debit posting. Order in the stream is commit order, the posting id."""
    pid: int
    src: str
    dst: str
    amount: int
    at: datetime
    score: int = 0


def parse_time(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).replace(tzinfo=None)


class Index:
    """Per-account positions in the stream, so a lookup can take exactly the postings before position i."""

    def __init__(self, stream):
        self.stream = stream
        self.ids = [p.pid for p in stream]
        if self.ids != sorted(self.ids):
            raise ValueError("the stream must be in posting id order, which is commit order")
        self.out, self.inc = {}, {}
        for i, p in enumerate(stream):
            self.out.setdefault(p.src, []).append(i)
            self.inc.setdefault(p.dst, []).append(i)

    # The only way features read the stream. bisect_left keeps position i itself out, and everything
    # after it, which is the whole point-in-time rule in one line.
    def before(self, table, account, i):
        rows = table.get(account, [])
        return rows[:bisect.bisect_left(rows, i)]

    def outgoing(self, account, i, limit=WINDOW_LIMIT):
        rows = self.before(self.out, account, i)
        return [self.stream[j] for j in reversed(rows if limit is None else rows[-limit:])]

    def incoming(self, account, i):
        return [self.stream[j] for j in reversed(self.before(self.inc, account, i))]


def _between(ps, lo, hi):
    return [p for p in ps if lo <= p.at <= hi]


def features_at(index, i):
    """Every feature for stream[i], from postings before it only."""
    e = index.stream[i]
    window = index.outgoing(e.src, i)
    incoming_all = index.incoming(e.src, i)
    incoming = [p for p in incoming_all if p.at >= e.at - HOUR]
    amounts = sorted(p.amount for p in window)
    median = amounts[(len(amounts) - 1) // 2] if amounts else 0
    inflow = sum(p.amount for p in incoming)
    near = 0
    for p in window:
        d = abs(p.at.hour - e.at.hour)
        near += min(d, 24 - d) <= 1
    flagged = [p for p in incoming if p.score >= FLAG]
    second = []
    for inc in incoming:
        j = bisect.bisect_left(index.ids, inc.pid)
        for p in index.incoming(inc.src, j):
            if p.at >= inc.at - HOUR and p.score >= FLAG and p.at <= inc.at:
                second.append(p.pid)
    f = {
        "outgoing_10m": len(_between(window, e.at - TEN_MIN, e.at)),
        "outgoing_60m": len(_between(window, e.at - HOUR, e.at)),
        "amount_minor": e.amount,
        "amount_to_median_x100": 0 if len(window) < 5 else e.amount * 100 // max(1, median),
        "history_size": len(window),
        "counterparty_known": int(any(p.dst == e.dst for p in window)),
        "distinct_payees_60m": len({p.dst for p in _between(window, e.at - HOUR, e.at)}),
        "hour_seen_count": near,
        "incoming_60m_minor": inflow,
        "passthrough_pct": 0 if inflow == 0 else e.amount * 100 // inflow,
        "fan_in_60m": len({p.src for p in incoming}),
        "payee_payers": min(PAYERS_CAP, len({p.src for p in index.incoming(e.dst, i)})),
        "payee_outgoing": len(index.outgoing(e.dst, i)),
        "flagged_in_60m_minor": sum(p.amount for p in flagged),
        "seed_hops": 1 if flagged else (2 if second else 0),
        "cycle_24h": int(_cycle(index, i)),
    }
    return f


def _cycle(index, i):
    e = index.stream[i]
    frontier, seen = [e.dst], {e.dst}
    for _ in range(CYCLE_HOPS):
        nxt = []
        for node in frontier:
            # The store filters on time first and then takes 200, so this does the same, in the same order.
            recent = [p for p in index.outgoing(node, i, limit=None) if p.at >= e.at - DAY][:WINDOW_LIMIT]
            for p in recent:
                if p.at > e.at:
                    continue
                if p.dst == e.src:
                    return True
                if p.dst not in seen:
                    seen.add(p.dst)
                    nxt.append(p.dst)
        frontier = nxt
        if not frontier:
            break
    return False
