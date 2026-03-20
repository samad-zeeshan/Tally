# java.util.logging with a single-line formatter

## Context

Requests need to be traceable through the logs, and the logs must never leak a secret or a full
account id. The service logs a few line shapes from one process. A new logging dependency would need to
earn its place against those modest needs.

## Decision

`java.util.logging`, configured programmatically, with one custom single-line `Formatter`. A parent
`dev.tally` logger with `setUseParentHandlers(false)` and one `ConsoleHandler` to stderr; the level
comes from `TALLY_LOG_LEVEL`, default INFO. The formatter emits one line per record: UTC instant,
level, `req=<id>`, logger, then the message as space-separated `key=value` pairs, with an exception's
frames indented on following lines as the only multi-line case. It reads the request id from a
`ScopedValue`, which is safe because `Handler.publish` runs synchronously on the thread that logged,
where the binding is still live.

Redaction is enforced by tests, not just review: account ids and idempotency keys are logged last-4
only, request bodies never, and the token never in any form. The access line logs the route template
(`/accounts/{id}`), never the raw path, so a UUID cannot ride into a log through the URL, and unmatched
paths are sanitized to printable ASCII against log injection.

## Alternatives

slf4j with logback: the standard pairing, but it adds two dependencies and XML configuration for
appenders, MDC, and rolling files this service does not use, against a philosophy of primitives you can
explain. A fully hand-rolled logger: it would reimplement the levels and handlers jul already has and
lose the `Handler` seam the log-capture tests rely on. No file handler: stderr suits a container, and a
rolling file under the repo path would fight OneDrive's sync locking on the development machine.

## Consequences

No MDC and no structured JSON logs; correlation is the single `req=` field. jul's ergonomics and
throughput are weaker than logback's, which does not bite at this size. If log volume grows, or a log
aggregator wants JSON, or the line shapes multiply, that is the trigger to revisit.

## Status

Accepted.
