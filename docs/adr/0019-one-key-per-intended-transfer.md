# One idempotency key per intended transfer

## Context

A flaky network can drop the response to a transfer the server already applied. The client cannot tell
"never arrived" from "applied, response lost", so a fresh key on every attempt would double-pay. The
backend deduplicates by idempotency key; the client has to use that key correctly for the guarantee to
hold end to end.

## Decision

Mint one key with `crypto.randomUUID()` at first submit, freeze it together with a snapshot of the
request body into an "intent", and reuse that exact key on every retry of that intent. The state machine
is a pure reducer: a transient failure (no response, or a 5xx) keeps the key and frozen body so a retry
is safe; a terminal failure (a definite 4xx) discards the key; editing the form abandons the intent. The
key enters the reducer as event payload, minted by the caller, so the reducer stays pure and unit
tested. Replay is read from the backend's `Idempotency-Replayed` response header, never from the status,
because a replay repeats the original 201, and the UI says out loud when a retry was deduplicated.

## Alternatives

A fresh key per attempt: the classic double-pay, it defeats the whole guarantee. Minting the key at form
mount: it forces key-rotation logic at mount, at success, and at edit, spread across effects, and makes
the reducer impure. Detecting replay by status: impossible, a replay and a first apply are both 201.

## Consequences

Retry must reuse the frozen body, not the current form values, or the same key would carry a different
tuple and hit a key conflict; so while a transient failure is showing, any field change abandons the
intent and the Retry button disappears. A user who abandons a transient failure never learns whether the
first attempt applied, so the UI says exactly that and points at the statement. The dropped-response dev
fault fires after the real request reached the server, because a failure before send would prove nothing
about deduplication; it simulates a lost response from inside the client, and the manual script also
covers the real thing (backend killed between submit and retry) so the demo does not rest on a simulated
fault alone.

## Status

Accepted.
