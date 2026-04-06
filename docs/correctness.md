# How Tally stays correct

Tally moves money between accounts with double-entry bookkeeping: every transfer is two postings that
sum to zero, so value is never created or destroyed. This note explains the invariants the system holds,
where each one is enforced, and how that enforcement is proven. It is written for someone who knows
software but not this repository.

## The invariants

**Conservation.** A transfer is a pair of postings, a debit and a credit, that sum to zero. Every opening
balance is itself a balanced transfer from a reserved `world` account, so the sum of every balance in the
book, including world's, is always exactly zero. `ConservationTest` states this over a random batch, and
the stress harness re-checks it after tens of thousands of concurrent transfers: the pool total before
must equal the pool total after.

**No silent negatives.** A debit that would take an ordinary account below zero is refused with
`INSUFFICIENT_FUNDS` (HTTP 422) and moves nothing. Only `world` may hold a negative balance, by rule,
because it is the negative of all money ever issued. `LedgerTest` proves the arithmetic and
`StoreContractTest.insufficientFundsLeavesBalancesUntouched` proves a rejected transfer leaves both
balances exactly as they were.

**Idempotency.** Each transfer carries a client `Idempotency-Key`. The key applies once; a retry returns
the first outcome, repeating the original status plus an `Idempotency-Replayed: true` header, so a client
that never saw the first response can retry safely without paying twice. Only `Applied` and
`InsufficientFunds` consume a key, because both write a durable record and must replay deterministically;
unknown-account, reserved-account, and validation failures write nothing and leave the key free.
`InMemoryStoreIdempotencyTest`, `IdempotencyHttpTest`, and the Postgres contract in `JdbcStoreTest` cover
this three ways.

**Atomic and durable.** Each Postgres transfer is one database transaction: it either commits whole or
leaves nothing. `JdbcCrashSafetyTest` injects a fault at the widest half-applied window, between the two
balance updates, and proves the transaction rolls back with no half-applied money and no consumed key.

**Money is never a float.** Amounts are a `long` count of minor units everywhere: `Math.addExact` in the
pure `Ledger` (so an overflow throws rather than wraps), an integer-only JSON parser that rejects a
fractional or exponent amount at the wire boundary (`JsonParseTest`), and `Number.isSafeInteger` guards
in the browser client. No floating-point value ever touches money, at any layer.

## Where each invariant is enforced

The guarantees are enforced at more than one layer, so a single one is defended everywhere it could be
broken. If a bug slipped past the store, the database constraints still catch it, and vice versa.

| Invariant | Store (in-memory) | HTTP edge | Database (Postgres) |
|---|---|---|---|
| Conservation | mirrored postings sum to zero; world funds every opening | — | `CHECK (amount_minor <> 0)`; reconciliation asserts `SUM(balance_minor) = 0` |
| No silent negatives | read-check-write under a per-account lock | — | `CHECK (balance_minor >= 0 OR allow_negative)` |
| Idempotency | a reservation map claims the key before applying | key format validated at the edge | `UNIQUE (idempotency_key)`, holding across restarts |
| Atomic and durable | the pure `Ledger` computes both sides together | — | one transaction per transfer, `synchronous_commit` on |
| Money never a float | `long` minor units, `Math.addExact` | integer-only parse, 10^12 cap | `bigint` columns |

The idempotency row is the interesting one: the in-memory reservation, the header semantics, and the
Postgres unique constraint are three independent enforcers of the same rule. The constraint is the
truth, because it is the only one that survives a process restart or would hold across multiple app
instances.

## The stress harness

A single harness fires a flood of concurrent transfers and then checks every invariant at once: N worker
threads each fire a share of M transfers between K accounts with random amounts, all released from one
start latch so they collide. In memory the standard run is K = 8 accounts, N = one thread per two cores
(clamped to 8–32), M = 20,000 transfers; an overdraft-pressure variant drops the opening balances so many
transfers are legitimately rejected for funds and the no-negative rule takes real fire. The same harness
runs against Postgres at K = 8, N = 8, M = 2,000 (fewer, because each is a full database transaction).

Afterward it asserts four things: the pool total is unchanged (conservation), no balance ever went below
zero, replaying the log of applied transfers reproduces the final balances exactly (exactly-once), and no
operation was lost. The await timeout doubles as a deadlock detector: a real deadlock never finishes, so
a run that does not complete in time fails and names the stuck threads.

## A real bug, reproduced on purpose

The concurrency safety was built the honest way, by writing a naive store first and making it fail.

*Symptom.* The naive store passes every single-threaded test. Under the harness it corrupts money. In one
overdraft-pressure run the pool started at 8,000 minor units and ended at 17,958, a delta of +9,958:
money created from nothing. A second demo fires sixteen threads at one idempotency key and watches it
apply three times.

*Cause.* A lost update. The transfer is a read-check-write with no mutual exclusion. Two threads read the
same balance, both compute a new one, and the second write erases the first:

```
thread A reads balance 1000        thread B reads balance 1000
thread A computes 1000 - 100 = 900 thread B computes 1000 - 100 = 900
thread A writes 900                thread B writes 900   (A's debit is gone)
```

The credit side lands twice while the debit lands once, so the book gains money. The duplicate-key case
is the same shape: two threads both see the key absent and both apply.

*Fix.* One lock per account, acquired lower-id-first in `UUID.compareTo` order. Taking the two locks in a
single total order across every transfer is what makes a waits-for cycle, and so a deadlock, impossible
when two transfers cross (A→B and B→A). The Postgres store reimplements the same argument with two
single-row `SELECT ... FOR UPDATE` statements in the same id order.

*Proof.* The naive store and its two failure modes are preserved in test scope
(`NaiveInMemoryStore`, `UnorderedLockStore`, `RaceDemoTest`, run with `-Prace-demo`), so the bug can be
re-summoned on demand. The real store passes the identical harness green, in memory and against Postgres,
on every CI run. The naive version was written to fail; that is the test-driven method working, and
keeping it in the repository is what lets the fix prove itself against the exact defect it closes.

## Reconciliation as the drift detector

Balances are stored, and they are also derivable by summing an account's postings. Tally keeps both.
`GET /reconciliation` recomputes every balance from its postings, compares it to the stored balance, and
checks the whole book still sums to zero. A bug that slips past every other check still shows up here as
drift, and the endpoint reports it as data (`consistent: false` with the offending accounts) rather than
hiding it behind an error. Against Postgres it runs in one read-only `REPEATABLE READ` transaction, so it
audits a single consistent snapshot even while transfers commit underneath it.

## The durability spot check

To spot-check durability against the running container: drive transfers in a loop, `docker compose kill
app` mid-loop, `docker compose up -d app`, then `GET /reconciliation`. It reports `consistent: true`,
`globalSumMinor: 0`, and no drift, because every transfer the caller was told succeeded was committed to
the write-ahead log before the response was sent. This is a spot check, not a proof; the crash-safety
test is the proof. One correctness note on the runtime: the store does blocking JDBC on a virtual-thread
executor, which is safe because `synchronized` no longer pins the carrier thread (JEP 491, delivered in
JDK 24 and present in the Java 25 the service runs on), so a blocked transfer frees its carrier for other
work instead of starving the pool.

## The deployed-demo auth trade

Write endpoints require a bearer token, proven server-side by the auth tests and by a `curl` to
`POST /transfers` with no `Authorization` header returning `401`. The container demo bakes a
`tally-local-dev-token` into the served JavaScript only so the one-origin demo's create and transfer
work. A token in served JavaScript is not protection: anyone who opens the page has it. A real client
would fetch a short-lived token from a login flow the demo does not build. This is the same limit
ADR-0015 records, restated here where a reader of the demo will meet it.

## Deliberately out of scope

- **Multi-currency.** Every amount is one implied currency; a real ledger carries a currency per account
  and never mixes them in a transfer. Out of scope to keep the money model one clear idea.
- **Real payment rails.** Nothing here talks to a bank or a card network; `world` stands in for the
  outside world.
- **Distributed anything.** One process, one database. The idempotency and locking arguments are written
  so they would extend to multiple app instances, but that is not built or tested.
- **Performance tuning.** Correct first. No connection-pool sizing study, no query plans beyond the one
  index the statement scan needs.
- **Formal verification.** The invariants are argued and tested, not machine-proven.
