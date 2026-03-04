# An idempotent key replays the first outcome

## Context

A transfer carries a client-chosen key so a retry after a timeout applies the money once.
Two threads carrying the same fresh key must apply exactly once, the loser must receive the
winner's outcome rather than a spurious error, and a recorded rejection must replay rather
than run again on retry. A check-then-put on any map races, even a thread-safe one.

## Decision

A repeated key replays the first recorded outcome. In memory the mechanism is a reservation:
`putIfAbsent` of an entry holding the request and a `CompletableFuture` slot. The first caller
wins, runs the transfer outside every map internal, and completes the slot. A duplicate with
the same request tuple joins the future, so it waits for and returns the winner's outcome. A
duplicate with a different tuple is a `KeyConflict`. Only the outcomes the store records,
`Applied` and `InsufficientFunds`, consume the key and replay; `UnknownAccount` and
`ReservedAccount` write nothing, so the winner completes the slot and then releases the
reservation, leaving the key free to retry once the cause is fixed. A replay is the value
`Replayed(first)`, not a boolean flag.

## Alternatives

`computeIfAbsent(key, k -> runTransfer())`. It runs the whole transfer, locks and balance
mutation included, inside a `ConcurrentHashMap` bin lock, which the map's contract says must
be a short computation that touches no other mapping. It serializes unrelated keys that
collide in a bin.

A global synchronized store. It reintroduces a coarse lock through the back door.

Storing only successes. A retried insufficient-funds could apply later, and "the first
result" becomes a lie.

A `replayed` boolean instead of a subtype. It threads a second field through every layer the
sealed `TransferOutcome` already models cleanly.

## Consequences

A duplicate arriving mid-flight blocks on the winner's future, which is the semantic the HTTP
retries want. The persistence layer replaces the reservation with
`INSERT ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING ...`, the same pattern enforced
by a unique constraint, so the contract survives the move to a database unchanged.

## Status

Accepted.
