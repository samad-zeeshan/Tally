# Per-account locks in id order

## Context

A single coarse lock over the whole store is correct but serializes every transfer,
including transfers between accounts that share nothing. Finer locks mean two locks per
transfer, and two-lock acquisition is the textbook deadlock setup: a transfer A to B and a
transfer B to A can each grab one lock and wait forever on the other.

## Decision

One `ReentrantLock` per account, held in a `ConcurrentHashMap` filled by `computeIfAbsent`,
acquired lower-id-first where "lower" is `UUID.compareTo`. Same-account transfers are rejected
before any lock, so the two ids are always distinct and the order is strict. Locks are never
removed, which is fine because accounts are never deleted, so the number of locks is bounded
by the number of accounts. Account creation takes the world lock and the new-account lock in
the same order, so a funding and a transfer cannot deadlock either.

## Alternatives

Keep the coarse lock. Correct, and it is kept in the history as the honest intermediate step,
but it is throughput-hostile and teaches nothing about real ledger contention.

`tryLock` with backoff and retry. No deadlock, but livelock-prone and harder to reason about
or test than a total order.

Lock striping by a hash of the id. Stripes couple unrelated accounts and add nothing over a
lock per account at this scale.

## Consequences

A deadlock needs a cycle in the waits-for graph. With one global acquisition order, every
edge points from a lower id to a higher id, so the graph is acyclic and a cycle cannot form,
whatever the thread count. Using the UUID's natural order is not about the value being
numeric: `UUID.compareTo` is a signed comparison, so the nil world UUID is not even the least
element. Consistency and totality, not magnitude, are what break circular wait. The database
store mirrors the same order with `SELECT ... FOR UPDATE` taken lower-id-first, so this
argument survives the move to Postgres unchanged.

## Status

Accepted.
