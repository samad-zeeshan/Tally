# Stored balances kept and reconciled, with keyset pagination

## Context

Two coupled questions on the read side of the ledger: is an account balance the source of truth or is
it derived from postings, and how is an append-heavy statement paginated when it is read newest first.
The book already sums to zero globally, because every opening is a balanced transfer from a reserved
counterparty, so a balance can in principle be recomputed from its postings alone. The question is
whether to keep both representations and check them against each other.

## Decision

Keep both. `accounts.balance_minor` is the fast read path, `SUM(postings.amount_minor)` is the auditable
truth, and a reconciliation check compares the two per account and asserts `SUM(balance_minor)` over the
whole book is zero. Each posting stores `balance_after_minor` at write time, a stable historical fact and
a third witness. Paginate statements by keyset on the posting id via the `(account_id, id)` index, with
an opaque versioned cursor `base64url("v1:" + postingId)` and a `limit + 1` probe for a definite
end-of-pages signal.

## Alternatives

Derived-only balance. Every read scans postings, and with a single source drift is undefined rather than
detectable.

Stored-only balance. Drift is invisible.

`OFFSET` pagination. Pages shift under concurrent inserts, and the cost grows with depth.

A raw posting-id cursor, or a timestamp cursor. The first freezes an internal detail into the public API
and invites fabricated cursors; the second is non-unique and its ties break the walk.

A read-time window-function running balance. Recomputes the whole history on every page.

## Consequences

Reconciliation asserts both the per-account `stored == derived` equality and the global `SUM == 0`, and
the global check is literally true because of the zero-sum book. Per-account posting ids strictly increase
in commit order, because the account row lock is held to commit, which is exactly what makes the sequence
id a safe keyset key and lets any snapshot see a gap-free prefix. The cursor is opaque, so clients cannot
depend on it being a posting id, and the `v1:` tag keeps a format change detectable. If multi-leg
transfers were ever added, `counterpartyAccountId` on the statement line is the field that would break,
since it assumes exactly two postings per transfer.

## Status

Accepted.
