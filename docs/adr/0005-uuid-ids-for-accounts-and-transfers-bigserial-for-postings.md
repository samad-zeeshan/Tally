# UUID ids for accounts and transfers, bigserial for postings

## Context

Accounts and transfers need a stable identity that can be generated without a round trip to
the store. Postings need a compact order for reading a statement. And the account id doubles
as the key that gives two concurrent transfers a fixed order to take their locks in, which is
what keeps them from deadlocking.

## Decision

`AccountId` and `TransferId` wrap a `UUID`, generated in process with `UUID.randomUUID()` and
carried on the wire as opaque lowercase strings. Posting ids are a monotonic `long`, a
database `bigserial` once there is a database, and the only value ever used as a statement
cursor. Two account locks are always taken lower-id-first by `UUID.compareTo`.

## Alternatives

Sequential `long` account ids. They are enumerable, so they leak how many accounts exist and
invite guessing, and generating one needs a round trip to whatever hands out the sequence.

A string natural key (an email, say). No free generation, a real collision risk, and it ties
identity to a value that can change.

## Consequences

The store never round-trips to learn an id. The ids map straight onto a `uuid` column later.
The lock order over accounts is total and global, so a circular wait between two transfers
cannot form. Postings keep a compact, ordered cursor. Account ids are not guessable, and auth
covers what little that leaves.

## Status

Accepted.
