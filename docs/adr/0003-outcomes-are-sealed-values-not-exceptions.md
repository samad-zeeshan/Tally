# Outcomes are sealed values, not exceptions

## Context

Applying a transfer has expected non-success outcomes: insufficient funds, an unknown
account named in the request, a client naming the reserved account. Every caller has to
branch on these. The same outcome will later need to be stored and replayed for an
idempotency key, and mapped to an HTTP status code.

## Decision

The store returns a sealed `TransferOutcome` with a record per case. A sealed type makes a
pattern-matching switch exhaustive at compile time, so a caller cannot forget an arm, and
the compiler flags a missed case when a new one is added.

## Alternatives

One exception type per rule. Control flow by exception is slow-path, easy to leave
uncaught, awkward to store for replay, and it couples the HTTP status mapping to exception
class names.

A bare boolean or an enum. Loses the per-case data a caller needs: which account was short,
the balance and the amount requested.

## Consequences

Callers pattern-match. Storing and replaying an outcome is natural, because it is a value.
The HTTP layer maps cases with one switch and no `default`. Exceptions are reserved for
programmer errors and impossible states: nulls, an empty posting list, a non-positive
amount at construction, or a store-built transfer that somehow fails to balance. The pure
balancing math keeps a separate internal result for its two arithmetic-only failures
(unbalanced, overflow), which can never occur on the store path and would surface as a 500
if they somehow did, so those never leak into the client outcome set.

## Status

Accepted.
