# Kubernetes layout: plain manifests, kind, and a migration job

## Context

Tally ran in two places: a JVM on a laptop and `docker compose`. Neither shows how the service behaves
when an orchestrator starts it, probes it, restarts it and scales it. The move had to keep the things
the compose setup already got right: no credential in the repository, the schema migrated before the
API serves, and a liveness check that never touches the database (ADR-0020).

## Decision

Plain YAML under `deploy/k8s/`, assembled with Kustomize, which ships inside `kubectl`. A `base`
holds everything, and two overlays, `local` and `ci`, change only what differs between a laptop and a
CI runner: resource sizes and the image tag.

- **Namespace** `tally`, so the whole system can be listed and deleted as one unit.
- **Postgres** is a StatefulSet with one replica and a `volumeClaimTemplate`, behind a headless
  Service. `pg_isready` is its readiness probe.
- **Migrations** run in a Job, `dev.tally.ops.Migrate`, using the same `MigrationRunner` the app uses.
  The API Deployment sets `TALLY_MIGRATE_ON_START=false` and has an init container that runs the same
  class with `--await`. It waits until every migration file in the image is recorded in
  `schema_version`. So a pod cannot start serving on an old schema, and two replicas never race to
  run the runner, which has no advisory lock (ADR-0012).
- **The API** is a Deployment of two replicas with readiness and liveness probes on `GET /health`,
  requests and limits on every container, a PodDisruptionBudget of `minAvailable: 1`, and a
  HorizontalPodAutoscaler from two to four replicas on 70 per cent CPU.
- **Config** that is not secret lives in a ConfigMap. The two secrets, `POSTGRES_PASSWORD` and
  `TALLY_API_TOKEN`, live in a Secret named `tally-secrets`. The Secret is not in the manifests. The
  up script creates it from the same gitignored `.env` compose reads, with
  `kubectl create secret generic --from-env-file`.
- **Reaching it from the host** is a NodePort, not an Ingress. The kind cluster config maps host
  ports 8080, 3000 and 9090 to the node ports of the API, Grafana and Prometheus.
- **The cluster** is kind. `scripts/k8s-up.sh` and `scripts/k8s-up.ps1` (and `make k8s-up`) create
  it, build the image, load it into the node, create the Secret, apply the overlay and wait.

## Alternatives

Helm. A chart is a template language over the same YAML, and this project has one deployment
target with two small variations. Kustomize patches cover that without a second tool.

Running migrations inside every API pod at startup, as compose does. With two replicas that is two
runners racing on `CREATE TABLE`, and the runner has no lock. Adding an advisory lock would work,
but a Job makes the ordering visible in `kubectl get` and keeps the API image's start path short.

A readiness probe that checks the database. ADR-0020 already rejected this for liveness. For
readiness it would pull every pod out of the Service during a database blip, which turns a partial
outage into a full one. The probe stays on `/health`.

An Ingress with an ingress controller. It is the right answer in a real cluster, but on kind it
means installing and waiting for a controller first, which adds a moving part to every CI run for
nothing the test needs.

Committing a Secret manifest with a placeholder. Someone eventually commits the real value.
Creating it from `.env` at apply time means the repository never holds one.

## Consequences

`make k8s-up` needs Docker, kind and kubectl, nothing else. The HPA needs metrics-server to act. kind
does not ship it, so on a bare kind cluster the HPA reports `<unknown>` CPU and holds at two
replicas. The up script installs metrics-server when asked (`WITH_METRICS_SERVER=1`), and the CI job
does not, because no gate depends on scaling.

The migration Job has a TTL, so it is deleted a while after it finishes. Applying the overlay again
recreates it and it runs again, which is safe because the runner skips applied files.

`GET /health` is no longer counted by the rate limiter (ADR-0021). The kubelet probes from the node
address, and behind a NodePort the outside callers can arrive from that same address. A probe that
gets a 429 marks a healthy pod unready. The route is a fixed string with no store behind it, so
exempting it costs nothing.

The web client is built with `TALLY_API_TOKEN` baked in, as in compose. That is still a demo
credential (ADR-0015). The image built by the up script is local to the kind node and never pushed.

## Status

Accepted.
