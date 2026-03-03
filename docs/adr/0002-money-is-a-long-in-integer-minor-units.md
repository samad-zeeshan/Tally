# Money is a long in integer minor units

## Context

Balances and amounts need exact arithmetic. Binary floating point cannot represent a value
as ordinary as `0.1`, so money held in a `double` drifts, and the drift compounds across a
history of transfers. A ledger cannot be off by a cent.

## Decision

Amounts and balances are a `long` of integer minor units (cents, for a single implicit
currency). Every addition of two money values goes through `Math.addExact`, so an overflow
throws instead of wrapping silently. No `double` or `float` ever touches an amount or a
balance, at any layer.

## Alternatives

`BigDecimal`. The right tool once several currencies with different minor-unit sizes and
rounding rules are in play, but heavier, allocating on every operation, and it invites scale
and rounding bugs for a single-currency ledger that never needs a fraction.

A `Money` wrapper type now. Ceremony until a currency field gives it a job. A `long` with a
documented convention carries the same guarantee with no indirection.

## Consequences

The range is about `9.2e18` minor units, far beyond any realistic balance. Overflow becomes
a visible, tested rejection rather than silent corruption. There is no rounding logic
anywhere in the system, because there is nothing to round.

## Status

Accepted.
