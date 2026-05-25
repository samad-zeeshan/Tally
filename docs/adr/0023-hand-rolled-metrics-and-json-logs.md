# Hand-rolled Prometheus metrics and JSON log lines

## Context

Once the service runs under an orchestrator, a person debugging it no longer has a terminal on the
box. They need numbers a scraper can collect and log lines a collector can parse. ADR-0016 kept the
logs as single `key=value` lines and named the trigger to revisit: a log aggregator that wants JSON.
Kubernetes is that trigger. Metrics did not exist at all.

## Decision

**Metrics.** A small `dev.tally.obs.Metrics` class holds counters and histograms in `LongAdder`s and
renders them in the Prometheus text format, version 0.0.4, by hand. `GET /metrics` serves it. The
series are:

- `tally_http_requests_total{method,route,status}` and
  `tally_http_request_duration_seconds{method,route}`. The route label is the matched template, such
  as `/accounts/{id}`, for the same reason the access log uses it: a raw path carries account ids and
  would give every account its own series.
- `tally_transfers_total{outcome}` with `applied`, `replayed` and `rejected`. Rejected is every store
  answer that moved no money: insufficient funds, unknown or reserved account, key conflict.
- `tally_reconciliation_duration_seconds`.
- `tally_db_pool_wait_seconds`, the time a request waits in `Pool.borrow`.
- `tally_rate_limited_total`.

Durations are measured in nanoseconds as `long` and written as decimal seconds by integer
arithmetic, so no floating point is involved even here.

`/metrics` sits behind the bearer token like every other route but `/health`. Request volumes per
route are not account data, but ADR-0015 made "everything but health needs the token" the rule, and
Prometheus can send a bearer token from a mounted Secret.

**Logs.** A second formatter, `JsonFormatter`, writes one JSON object per line: `ts`, `level`,
`requestId`, `logger`, `msg`, and each `key=value` pair of the message as its own field. The request
id is the same one returned in `X-Request-Id`. `TALLY_LOG_FORMAT=json` selects it. The default stays
`line`, which is easier to read in a terminal, and the Kubernetes ConfigMap sets `json`. The JSON is
built with the project's own `JsonValue` writer, so escaping has one implementation.

## Alternatives

The Prometheus Java client or Micrometer. Both are fine libraries and both would be the first
runtime dependency after the JDBC driver. The text format is a page of rules, and the set of series
here is fixed and small.

OpenTelemetry with an OTLP exporter. It would add a collector to the cluster and an SDK to the jar
to carry the same six series.

Switching the default log format to JSON. It would make local runs harder to read and change the
shape the existing log tests capture, for no gain on a laptop.

Leaving `/metrics` open. Easier to scrape, but it would be the second unauthenticated route and the
first one that says anything about traffic.

## Consequences

The metrics are process-local. With two replicas each pod reports its own counters, and Prometheus
discovers pods, not the Service, so the dashboards sum over pods. A restart resets counters, which
Prometheus `rate()` already handles.

There are no exemplars, no summaries and no per-account labels, on purpose. Adding a series means
adding a field to `Metrics` and a line to the dashboard JSON.

ADR-0016 is amended: JSON output now exists, behind a switch, and redaction still applies because
both formatters render the same already-redacted message.

## Status

Accepted.
