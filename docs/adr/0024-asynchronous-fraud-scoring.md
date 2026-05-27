# Asynchronous fraud scoring in the same service

## Context

Every applied transfer should get a fraud score soon after it commits: a number, and the list of
rules that produced it. The score is advice for a person or a later process. It must never decide
whether money moves, and it must never make a transfer slower. A scorer that shares the request
thread would do both the moment it got slow or failed.

## Decision

**Where it runs.** In the same Java service, in a new `dev.tally.fraud` package. One image, one
Deployment, one set of probes. A separate service would need its own transport, deployment and
failure handling, and nothing about the scoring load asks for independent scaling yet.

**How a posting gets there.** After the store answers `Applied`, the transfers handler builds a
`PostingEvent` (the debit posting id, the credit posting id, the transfer, both accounts, the amount
and the time) and calls `offer` on a bounded in-memory queue of 10,000 events. `offer` never blocks.
A full queue drops the event and counts it in `tally_fraud_dropped_total`. A replay (`Replayed`)
publishes nothing, because its posting was already published the first time. So the transfer path
gains one allocation and one non-blocking call, and no dependency on the scorer's speed or health.

**Who scores it.** One consumer on a virtual thread takes events in order and scores each debit
posting. The scorer keeps no state of its own. For each event it reads the account's recent scored
postings back from the score store, up to 200, and runs five deterministic rules over them:

- `velocity`: three or more earlier outgoing postings in the last ten minutes.
- `amount_deviation`: at least five times the median of the account's own earlier amounts, once
  there are five to compare with.
- `new_counterparty`: a payee the account has not paid in its window, once it has paid three.
- `round_amount`: a multiple of 100.00, from 500.00 up.
- `time_of_day`: an hour of day with no earlier outgoing posting within an hour either side, once
  there are ten.

Each rule adds integer points. The score is their sum, capped at 100. A score of 40 or more counts
as flagged. No floating point is involved, the same rule as money.

**Where it lands.** A `posting_scores` table from migration `002`, keyed on the debit `posting_id`.
The insert is `ON CONFLICT (posting_id) DO NOTHING`, and the in-memory store uses `putIfAbsent`, so
a posting delivered twice is scored once. The scorer checks first and skips a posting that already
has a score, counting it in `tally_fraud_duplicates_total`.

**Reading it.** `GET /accounts/{id}/risk` returns the account's latest scores with their rules,
behind the bearer token like every other route.

**Time.** A rule reads the posting's time from the event, which is the ledger's commit time. The
offline evaluation has to replay thirty days of synthetic traffic in a minute, so a flag,
`TALLY_FRAUD_REPLAY_CLOCK=true`, lets a transfer carry `X-Tally-Event-Time`, and only the scorer
reads it. The ledger still stamps its own time. The flag is off unless set, and no manifest sets it.

## Alternatives

Scoring inside the transfer transaction. The simplest code, and the one this decision exists to
rule out. A slow rule becomes a slow transfer, and a scorer bug becomes a failed transfer.

A durable queue such as Redpanda or Kafka, as in the Tarn project. It would survive a crash with
events still queued. It would also add a broker to every environment, a client library to the jar,
and a second system to keep healthy for an advisory score. It is not built. The `PostingSink`
interface is where it would plug in.

An outbox table written in the transfer transaction. Durable without a broker, but it adds a write
to the one transaction that must stay short, and a poller to drain it.

Keeping each account's window in memory in the scorer. Faster, but every restart empties it, and
with two API replicas each pod would see about half of an account's traffic. Reading the window
from the score store makes it survive restarts and shared by replicas, for one indexed read per
posting on the scorer thread, off the transfer path.

A trained model. There is no real labelled data here, and the point is rules a person can read
and argue with. The offline evaluation measures how good they are.

## Consequences

Scoring is at most once per posting, not exactly once. A crash loses whatever was queued, and a
full queue drops events. Both are counted where they can be. A backfill that rescans `postings` for
rows with no score would close that gap and is not built.

Two replicas can score two postings of the same account at the same moment, and then neither sees
the other in its window. The rules undercount a little in that race. That is the price of not
locking anything.

The window only holds postings that were scored. Traffic from before scoring was switched on, or
dropped events, is invisible to the rules.

The scorer uses a connection from the same pool as the transfers, at most one at a time.

## Status

Accepted.
