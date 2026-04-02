# CI on real Postgres, one shaded jar, the app serves the client

## Context

The project has to run anywhere, not just on the machine it was written on, and the safety story has to
be checked by a machine on every change, not asserted. That means continuous integration that runs the
same tests a reviewer would, a single artifact that starts with one command, and a container that brings
the whole thing up.

## Decision

CI runs on GitHub Actions as two parallel jobs, `backend` and `web`, so a web failure never masks a
backend result and vice versa. The backend job runs `./mvnw -B -Pintegration verify` against a real
`postgres:17` service container, so the concurrency, idempotency, and crash-safety tests execute against
the same database the app uses, not a stub. The profile id `integration` matches the `-P` flag exactly:
an unknown id is only a warning and would go green having run zero integration tests, which is the
failure this setup exists to prevent.

The app ships as one executable jar built by the shade plugin, `target/tally.jar`, with the PostgreSQL
driver shaded in and its `META-INF/services/java.sql.Driver` entry preserved by the services transformer.
The container serves the built web client from the Java server itself through the stdlib static fallback,
so the API and the client answer on one origin. Health is a liveness-only `GET /health` that touches no
database, checked by our own `HealthProbe` class so the image needs no curl.

## Alternatives

One serial CI job: slower feedback, and a web failure would hide backend results. A manifest `Class-Path`
with copied dependency jars, or jlink: two artifacts to keep in sync, or more machinery than a single
driver justifies. An nginx sidecar to serve the client: a second config language and a reopened
cross-origin surface, where one origin instead retro-justifies the client's no-CORS stance. Checking the
database inside the health endpoint: it would turn a database blip into a container restart loop, so the
deep check stays reconciliation.

## Consequences

The shaded jar is larger than the app's own classes because it carries the driver, which is the point,
one file that just runs. The container couples the client and API lifecycles, acceptable for a demo and
what makes the one-origin story true. A liveness check that ignores the database means the container can
be healthy while the database is down; that is correct, an app that cannot reach its database should not
be killed and restarted, it should surface the error and let reconciliation report drift.

## Status

Accepted.
