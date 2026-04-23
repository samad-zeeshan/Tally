# Rate limits, security headers, and signed cursors

## Context

A security pass over the edge found five things the earlier stages had left, and they are related: each
is a place where the server trusted something it had no reason to trust.

Nothing throttled anything. One static bearer token (ADR-0015) with unlimited guesses is a brute-force
target, and `POST /transfers` could be flooded by anyone who found the port. No response carried a
browser security header, even though this same server hands out the built SPA at the same origin
(ADR-0020), so a stored-XSS bug anywhere in the client had nothing standing in its way. The statement
cursor was `base64("v1:" + postingId)` and therefore a number the client asserted rather than a token the
server issued; it was contained only because the query is also scoped by the account in the path, which
is a property of a different check. The static file handler tested paths with `startsWith(root)` and
`Files.isRegularFile`, a lexical test followed by one that follows symlinks. And the compose file carried
a literal password and a literal API token in a public repository.

## Decision

**A fixed-window throttle in the kernel, keyed on the socket peer.** Two budgets share one window:
300 requests a minute, and 10 failed authentications a minute. The failure budget is an order of
magnitude smaller because that is the brute-force path, and spending it throttles the address for every
endpoint, not just its next auth attempt, so a guesser cannot keep working the API while it waits.
Exceeding either returns `429` with `Retry-After` and the ordinary error envelope, under a new
`RATE_LIMITED` code. The limiter runs before dispatch, so a throttled request never reaches a handler or
the store: the same ordering argument ADR-0015 makes for auth, one step earlier.

The key is the socket peer and never `X-Forwarded-For`. A client-supplied header would let one attacker
mint unlimited buckets, which turns the limiter into the memory exhaustion it exists to prevent. The map
is bounded at 10,000 addresses and swept once a window; at capacity, after a sweep, new addresses are
shed with a `429`. That is load shedding under a distributed flood, and it is the better failure: an
unbounded map runs the process out of memory and denies service to everyone, not just to the arrivals.

**Security headers on every response**, written in `HttpKernel.write` so no path out of the method skips
them: `nosniff`, `X-Frame-Options: SAMEORIGIN` (not `DENY`, so the project keeps the option of embedding
its own UI), `Referrer-Policy: no-referrer`, `Permissions-Policy` denying camera, microphone, and
geolocation, and a CSP written against the actual Vite output. Vite emits external module scripts, so
`script-src` stays `'self'`; React writes inline style attributes, so `style-src` takes `'unsafe-inline'`
and nothing else does. The client keeps one inline script, the pre-paint theme setter, and it is
permitted by a `sha256-` hash rather than by relaxing `script-src`; a test re-derives that hash from
`web/index.html` so the two cannot drift silently.

No `Strict-Transport-Security`. This server speaks plain HTTP locally, and an HSTS header served over
`http://localhost` pins the whole localhost origin to https in the developer's browser for `max-age`,
which breaks every other local project and is painful to undo. TLS is terminated by a proxy in any real
deployment, and the header belongs to whatever owns the certificate.

**The cursor is signed** with HMAC-SHA-256 truncated to 128 bits, compared with `MessageDigest.isEqual`
the way `Auth` compares the token. The key is derived from the configured API token with a label, so it
is a different key from the credential and needs no configuration of its own. A tampered, unsigned, or
foreign cursor is the existing `400 INVALID_CURSOR`, with one message for every cause.

**The static handler compares real paths.** `toRealPath()` on the resolved path, checked against the real
root. The lexical `startsWith` stays as a cheap first pass.

**Every credential comes from the environment.** Compose interpolates `${VAR:?message}` for both the
Postgres password and the API token, so an unset variable stops the run, and Postgres is published on
`127.0.0.1:5432` rather than every interface. `.env.example` is the committed template; `.env` is not.

## Alternatives

A token bucket or sliding window instead of fixed windows: smoother, but it needs per-address state that
decays continuously, and the fixed window makes expiry, eviction, and `Retry-After` one rule. Evicting
the least recently seen entry at capacity instead of shedding: an attacker could then evict a
locked-out address and reset its own penalty. Honoring `X-Forwarded-For` behind a trusted-proxy list:
correct for a real deployment, and worth doing when there is a real deployment to configure it against;
guessing which hop to trust is worse than not looking. `LinkOption.NOFOLLOW_LINKS` on the final path
component: does not close the hole, because the escaping hop can be a directory further up, and it
would also reject a link that stays inside the root, which is not what the rule is about. Putting the
theme script in its own file so `'self'` covers it: works, but adds a request before first paint to
avoid one hash. Keeping the cursor unsigned because the account scope contains it: true today, and it
makes the containment depend on a check somewhere else staying exactly as it is.

## Consequences

The limits are process-local, so two app instances behind a load balancer each allow the full budget;
the numbers are a floor, not a distributed quota, and a real deployment puts a limiter at the proxy too.
The failure budget can lock out a legitimate client that misconfigures its token, for at most a minute,
which is the intended trade. Rotating `TALLY_API_TOKEN` invalidates every outstanding cursor, so a client
paging through a statement across a rotation restarts it. Editing the client's inline theme script
without regenerating the CSP hash breaks the theme; the test that re-derives the hash is what catches
that. `docker compose up` now needs a `.env`, which is one more step for a new developer and the reason
`.env.example` exists.

## Status

Accepted.
