# Status codes split rule rejections from client mistakes

## Context

A money API needs distinct, deliberate status codes so a client and its retry middleware can
tell a client mistake from a rule rejection from a key collision, and it needs idempotency at
the HTTP boundary.

## Decision

The four-line rule: 400 for a request that could never be valid in any ledger state, 422 for a
well-formed request the current ledger state refuses, 409 only for an idempotency-key collision,
404 only for the resource named in the path. So insufficient funds is 422; an unknown account
referenced in a transfer body is 422 while an unknown account in a path is 404; a client naming
the reserved world account is 422 `RESERVED_ACCOUNT`; a replay returns the original status (201,
or 422 for a stored rejection) and body plus `Idempotency-Replayed: true`; the same key with a
different tuple is 409; a missing or malformed key is 400. Validation uses granular SCREAMING_SNAKE
codes, each carrying a `field` key. The envelope is `{"error":{"code","message","field"?}}`, and
tests assert `code` and a non-blank `message`, never whole-body equality, so a later `requestId`
field is non-breaking.

## Alternatives

409 for insufficient funds. Misstates who must change what: the world, not the request.

404 for an unknown account in a body. Claims `/transfers` is missing and confuses retry middleware.

200 on replay. Forks the client's success handling and defeats the indistinguishability idempotency
promises.

One coarse validation code. Loses the field-level signal and forces a rename when stricter rules
land, which is exactly the churn to avoid.

Storing every non-recording failure against a key. Blocks reusing a free key after an unknown-account
or reserved-account rejection, for zero benefit.

## Consequences

Clients branch on one rule. Only an applied transfer and an insufficient-funds rejection consume the
key, so a key is free to reuse after an unknown-account rejection, and an insufficient-funds retry
deterministically replays its 422. Account creation is not idempotent here, so a retried create opens
a second account funded again from the reserved account; the book still sums to zero, revisited only
if account creation ever needs a key.

## Status

Accepted.
