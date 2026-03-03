# A world account funds openings and may go negative

## Context

An account needs an opening balance, and the whole book has to stay reconcilable: every
unit of money must be recomputable from postings, so drift between a stored balance and its
history is detectable. If money can appear on an account without a matching posting, that
guarantee is gone.

## Decision

A single reserved `world` account, the nil UUID, name `world`, is the counterparty for
every opening. Creating an account with an opening balance is a balanced transfer
`world -> account`, not a mint. World is the one account allowed to go negative; its balance
is always the negative of all money ever issued, so the whole book, every account including
world, sums to exactly zero at every instant. A client naming world in a transfer is
refused.

## Alternatives

Mint the opening balance straight onto the new account. Rejected: the global sum then equals
"everything ever opened", a figure the ledger records nowhere, so conservation stops being
checkable and a balance can no longer be recomputed from its postings.

## Consequences

Every unit of money is a posting, so a reconciliation check that recomputes balances from
postings and asserts the whole book sums to zero is literally true. Exactly one account,
world, carries the "may go negative" rule, which is the escape clause the non-negative rule
allows for. Opening an account is visible as a funding transfer, not a silent balance edit.

## Status

Accepted.
