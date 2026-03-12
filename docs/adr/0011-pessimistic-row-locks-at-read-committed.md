# Pessimistic row locks at read committed

## Context

Application locks do not hold across processes or restarts, so the concurrency safety has to move to
the database. A transfer is a read-modify-write of two balance rows plus a few inserts. The anomalies
that could bite: a lost update (fatal here), a dirty read (impossible in Postgres at any isolation
level), a non-repeatable read or write skew (they only affect a transaction that depends on rows it
does not lock, and this one depends only on the two account rows it locks).

## Decision

READ COMMITTED, with two single-row `SELECT ... FOR UPDATE` in ascending `UUID.compareTo` order, the
unique constraint on `transfers.idempotency_key` as the idempotency arbiter, and one transaction per
transfer. Two separate lock statements, not one `WHERE id IN (?, ?) ORDER BY id FOR UPDATE`, so the
lock acquisition order is a contract and not a query-plan detail.

## Alternatives

REPEATABLE READ. It would still need `FOR UPDATE` for the balance check, and it adds 40001
serialization failures when a locked row changed after the snapshot, so it buys nothing here.

SERIALIZABLE. Correct without explicit locks, but it demands a 40001 retry loop and unpredictable SSI
aborts under the stress load, machinery for anomalies this access pattern cannot produce.

Optimistic version columns. Retries move into the application on every hot-account collision, worse
under contention, and the pessimistic story is the one worth telling in SQL.

## Consequences

No retry loop anywhere. Deadlock is ruled out by one global lock order, the same argument as the
in-memory store, now in SQL: every transfer takes its account locks lower-UUID-first, so no waits-for
cycle can form. Two row locks are held for microseconds per transfer. Single-statement reads see a
per-statement snapshot, which is fine for them. Durability is the Postgres default: `synchronous_commit
= on` means a commit returns only after the WAL fsync, so a transfer the caller was told succeeded
survives a restart.

## Status

Accepted.
