"""
Generate a seeded, labelled synthetic payment dataset for the fraud scorer's offline evaluation.

Thirty days of ordinary traffic between customers and merchants, with four fraud patterns injected in
the last ten days: burst, structuring, account takeover and mule chain. Writes the data and the ground
truth side by side. Same seed, same files, byte for byte.
"""

import argparse
import csv
import json
import random
from datetime import datetime, timedelta, timezone
from pathlib import Path

START = datetime(2026, 8, 1, tzinfo=timezone.utc)
DAYS = 30
# Fraud starts after day 20, so every victim has twenty days of habits for the rules to compare with.
FRAUD_FROM_DAY = 20
OPENING = 30_000_000          # 300,000.00, enough that no ordinary payment is refused for funds
MULE_OPENING = 200_000        # a little cash so a mule's cover payments go through
REPORTING_LINE = 1_000_000    # 10,000.00, the threshold structuring stays under


def lognormal_amount(rng, median):
    # Clamped at 1.00 so a tiny persona never produces a zero or negative amount.
    return max(100, int(median * rng.lognormvariate(0, 0.45)))


class Builder:
    def __init__(self, seed):
        self.rng = random.Random(seed)
        self.accounts = []      # (key, kind, opening_minor)
        self.transfers = []     # (event_time, from, to, amount, pattern, episode)

    def account(self, key, kind, opening):
        self.accounts.append((key, kind, opening))
        return key

    def pay(self, at, src, dst, amount, pattern="normal", episode=""):
        self.transfers.append((at, src, dst, int(amount), pattern, episode))


def build(seed, customers, merchants, episodes_per_pattern):
    b = Builder(seed)
    rng = b.rng
    shops = [b.account(f"m{i:03d}", "merchant", 0) for i in range(merchants)]
    people = []
    for i in range(customers):
        key = b.account(f"c{i:03d}", "customer", OPENING)
        people.append({
            "key": key,
            "hour": rng.randint(8, 20),               # the middle of this person's day, UTC
            "rate": rng.uniform(0.6, 2.5),            # payments a day
            "median": rng.choice([1_500, 3_000, 6_000, 12_000, 20_000]),
            "payees": rng.sample(shops, rng.randint(3, 8)),
        })
    for p in people:
        p["payees"] += rng.sample([q["key"] for q in people if q is not p], rng.randint(1, 3))

    # Ordinary traffic. Some of it looks odd on purpose (a rent-sized round payment, a new shop, a
    # shopping spree), because a scorer tested only on tidy traffic has an honest-looking false positive
    # rate of zero.
    for p in people:
        for day in range(DAYS):
            n = rng.random() < 0.02 and rng.randint(3, 5) or _poisson(rng, p["rate"])
            spree = n >= 3 and rng.random() < 0.3
            base = START + timedelta(days=day, hours=p["hour"]) + timedelta(minutes=rng.randint(-150, 150))
            for k in range(n):
                offset = timedelta(minutes=k * rng.randint(2, 6)) if spree else timedelta(minutes=rng.randint(-180, 180))
                at = base + offset
                payee = rng.choice(p["payees"]) if rng.random() > 0.05 else rng.choice(shops)
                amount = lognormal_amount(rng, p["median"])
                if rng.random() < 0.03:
                    amount = max(50_000, round(p["median"] * 20, -4))   # rent, a round hundred
                b.pay(at, p["key"], payee, amount)

    fraud_window = (FRAUD_FROM_DAY, DAYS - 1)
    victims = rng.sample(people, 4 * episodes_per_pattern)
    for e in range(episodes_per_pattern):
        burst(b, victims[4 * e], f"burst-{e:02d}", fraud_window)
        structuring(b, victims[4 * e + 1], f"structuring-{e:02d}", fraud_window)
        takeover(b, victims[4 * e + 2], f"takeover-{e:02d}", fraud_window)
        mule_chain(b, victims[4 * e + 3], f"mule-{e:02d}", fraud_window, shops)

    b.transfers.sort(key=lambda t: (t[0], t[1], t[2], t[3]))
    return b


def _poisson(rng, lam):
    # Knuth's method: fine for the small rates here, and it keeps the generator to the standard library.
    limit, k, p = pow(2.718281828459045, -lam), 0, 1.0
    while True:
        p *= rng.random()
        if p <= limit:
            return k
        k += 1


def _fraud_day(rng, window):
    return START + timedelta(days=rng.randint(*window))


def _new_beneficiary(b, episode, n=0):
    return b.account(f"x-{episode}-{n}", "beneficiary", 0)


def burst(b, victim, episode, window):
    """Many payments out within minutes, to one to three accounts the victim has never paid."""
    rng = b.rng
    at = _fraud_day(rng, window) + timedelta(hours=victim["hour"], minutes=rng.randint(-60, 60))
    targets = [_new_beneficiary(b, episode, i) for i in range(rng.randint(1, 3))]
    for _ in range(rng.randint(6, 12)):
        at += timedelta(seconds=rng.randint(20, 90))
        b.pay(at, victim["key"], rng.choice(targets), lognormal_amount(rng, victim["median"] * 2), "burst", episode)


def structuring(b, victim, episode, window):
    """Several round payments just under the reporting line, spread over days, inside normal hours."""
    rng = b.rng
    day = _fraud_day(rng, (window[0], window[1] - 3))
    targets = [_new_beneficiary(b, episode, i) for i in range(rng.randint(1, 2))]
    for _ in range(rng.randint(4, 7)):
        day += timedelta(hours=rng.randint(6, 16))
        at = day.replace(hour=victim["hour"]) + timedelta(minutes=rng.randint(-90, 90))
        amount = REPORTING_LINE - rng.randint(1, 10) * 10_000     # 9,000.00 to 9,900.00
        b.pay(at, victim["key"], rng.choice(targets), amount, "structuring", episode)


def takeover(b, victim, episode, window):
    """A stolen login: large payments at an hour the owner never uses, to a new account."""
    rng = b.rng
    hour = (victim["hour"] + 12 + rng.randint(-2, 2)) % 24
    at = _fraud_day(rng, window).replace(hour=hour, minute=rng.randint(0, 59))
    target = _new_beneficiary(b, episode)
    for _ in range(rng.randint(1, 3)):
        at += timedelta(minutes=rng.randint(2, 15))
        b.pay(at, victim["key"], target, victim["median"] * rng.randint(15, 40), "account_takeover", episode)


def mule_chain(b, victim, episode, window, shops):
    """A victim pays a mule, and the money hops through two or three more accounts within minutes."""
    rng = b.rng
    hops = rng.randint(3, 4)
    mules = [b.account(f"u-{episode}-{i}", "mule", MULE_OPENING) for i in range(hops)]
    exit_account = _new_beneficiary(b, episode)
    at = _fraud_day(rng, window) + timedelta(hours=victim["hour"], minutes=rng.randint(-60, 60))
    # Mules look like thin, ordinary accounts: a few small shop payments in the days before.
    for mule in mules:
        for d in range(rng.randint(2, 5)):
            cover = at - timedelta(days=d + 1, hours=rng.randint(0, 6))
            b.pay(cover, mule, rng.choice(shops), lognormal_amount(rng, 2_000))
    amount = victim["median"] * rng.randint(10, 30)
    b.pay(at, victim["key"], mules[0], amount, "mule_chain", episode)
    chain = mules + [exit_account]
    for i in range(hops):
        at += timedelta(minutes=rng.randint(3, 20))
        amount = amount * rng.randint(92, 98) // 100
        b.pay(at, chain[i], chain[i + 1], amount, "mule_chain", episode)


def write(b, out, seed):
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "accounts.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["account", "kind", "opening_minor"])
        w.writerows(b.accounts)
    with open(out / "transfers.csv", "w", newline="", encoding="utf-8") as f, \
            open(out / "labels.csv", "w", newline="", encoding="utf-8") as g:
        tw = csv.writer(f, lineterminator="\n")
        lw = csv.writer(g, lineterminator="\n")
        tw.writerow(["seq", "event_time", "from", "to", "amount_minor"])
        lw.writerow(["seq", "is_fraud", "pattern", "episode"])
        for seq, (at, src, dst, amount, pattern, episode) in enumerate(b.transfers, start=1):
            tw.writerow([seq, at.strftime("%Y-%m-%dT%H:%M:%SZ"), src, dst, amount])
            lw.writerow([seq, int(pattern != "normal"), pattern, episode])
    counts = {}
    for t in b.transfers:
        counts[t[4]] = counts.get(t[4], 0) + 1
    meta = {
        "seed": seed,
        "start": START.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "days": DAYS,
        "fraud_from_day": FRAUD_FROM_DAY,
        "accounts": len(b.accounts),
        "transfers": len(b.transfers),
        "transfers_by_pattern": dict(sorted(counts.items())),
    }
    (out / "meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8", newline="\n")
    return meta


def main():
    parser = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    parser.add_argument("--seed", type=int, default=20260925)
    parser.add_argument("--customers", type=int, default=180)
    parser.add_argument("--merchants", type=int, default=40)
    parser.add_argument("--episodes", type=int, default=15, help="episodes per fraud pattern")
    parser.add_argument("--out", type=Path, default=Path(__file__).resolve().parent)
    args = parser.parse_args()
    meta = write(build(args.seed, args.customers, args.merchants, args.episodes), args.out, args.seed)
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
