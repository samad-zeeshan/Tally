# A static bearer token checked at the edge

## Context

The service now takes requests from untrusted callers, so the writes that move money need protection.
The subtle part is not the check itself but where it sits: the idempotency key must not be consumable
by an unauthenticated caller, or a rejected request could burn or reserve a key it was never allowed to
use. Auth and the key handling have to be ordered, not just present.

## Decision

One static bearer token, read once from `TALLY_API_TOKEN`. Every route requires it except `GET /health`,
which must answer a liveness probe without a credential and returns a fixed string that names no account.
Auth is a handler wrapper applied at route
registration, so the protected routes are visible in one screenful, and it runs after routing but
**before the body is read**: the body is a lazy supplier, and the wrapper only ever touches headers.
That ordering is what makes the idempotency guarantee free: a 401 is produced before the
`Idempotency-Key` header is consulted, so an unauthenticated or wrong-token request cannot consume or
reserve a key.

The token is compared by digesting both sides to a fixed 32 bytes and calling `MessageDigest.isEqual`,
which is constant-time for equal-length inputs; digesting first also removes its length-mismatch early
return. A plain `String.equals` would leak the secret one byte at a time to anyone who can time the
response. Startup fails closed: a missing, blank, or under-16-character token stops the process with a
one-line message and exit code 2. Only 401 is ever returned, always with `WWW-Authenticate`; 403 is
never used, because one static token has no permission model to deny.

## Alternatives

Per-user auth or JWTs: there is no user model in scope, and a real identity system is a project of its
own. HMAC request signing: stronger against replay, but it obscures the one lesson this endpoint
teaches. A `com.sun.net.httpserver.Filter`:
filters attach per context, and everything routes through the one kernel context, so a filter would
duplicate the router's matching. Auto-disabling auth when the env var is unset: a foot-gun that ships an
open write API by accident.

Leaving reads open, which is what this decision originally said: writes and `GET /reconciliation` were
protected and the plain reads were not, traded for letting a reader browse balances with `curl` and no
setup. The gap was named rather than hidden, which is the right way to carry a known gap, but it was
still a gap: anyone who found the host could list every account and read every statement. The
convenience was not worth it, and the client already sent the token on every read, so closing it cost
nothing. Superseded, and the wrapper now goes on every route but `/health`.

## Consequences

A single shared secret means the logs can say a write happened but not who made it; rotation is a
restart. Rotating it also invalidates outstanding statement cursors, because ADR-0021 derives their
signing key from this token; that is the safe direction for a token to fail in. HTTP timeout policy stays
coarse: `com.sun.net.httpserver` has no per-exchange deadline, only
the process-wide `sun.net.httpserver.maxReqTime` / `maxRspTime` / `maxReqHeaderSize` limits set at
startup, and when one fires the client sees a dropped connection, not a clean 503. A reverse proxy owns
real timeout policy in production; that is out of scope and said out loud. Rate policy is no longer out
of scope: a single static token with unlimited guesses is a brute-force target, so the kernel throttles
failed authentication by client address. ADR-0021 covers it. Reconciliation is protected despite being a
read, because it recomputes every balance and is the one endpoint cheap to abuse.

The token itself is never a value in this repository. Compose reads it from a gitignored `.env` with no
default, so an unset variable stops the run rather than starting a service on a published constant.

## Status

Accepted. Amended: reads are protected too, and the edge throttles failed authentication (ADR-0021).
