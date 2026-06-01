# Tally

Tally is a small banking service built on a double-entry ledger, with a Java backend, a Postgres store, and a React client.

![Tally: a sample transfer, an idempotent retry applied exactly once, and a reconciliation that re-derives every balance from the postings](docs/media/demo.gif)

## What it does

Accounts hold balances. A transfer moves money between two accounts by writing two postings, one negative and one positive, that sum to zero. Because every movement is recorded that way, the whole book always nets to zero, and a reconciliation endpoint proves it on demand by re-deriving every balance from the postings. Each account has a paginated statement. Transfers are idempotent: the client sends a unique key with each intended transfer, and if the response is lost, retrying with the same key applies the transfer exactly once. The web client shows all of this, including a fault toggle that drops a response so you can watch the retry get deduplicated. There is also an in-app "Try it" strip that runs these flows for you.

## How it works

A request passes through one HTTP edge that throttles it and checks the token, then a handler, then a store that writes each transfer to Postgres in a single transaction.

![Tally: system overview](docs/diagrams/overview.png)
The main parts of the Java service, and how the Postgres or in-memory store gets picked at startup.

![Tally: one transfer and its retry](docs/diagrams/main-flow.png)
A transfer is applied once, its reply is lost, and a retry with the same idempotency key gets back the stored result.

![Tally: data model](docs/diagrams/data-model.png)
The three ledger tables from `db/migrations/001_init.sql`, plus the constraints that back the code's rules.

![Tally: local deployment with docker compose](docs/diagrams/deployment.png)
What `docker compose up --build` builds and runs, and where each secret comes from.

Interactive versions with pan, zoom and theme switch (data-model.html has the theme switch only): `docs/diagrams/overview.html`, `docs/diagrams/main-flow.html`, `docs/diagrams/data-model.html`, `docs/diagrams/deployment.html`


## Design decisions

The reasoning lives in [docs/adr/](docs/adr/), one decision per file. The ones that shape the project:

- **Double-entry postings, with a world account.** Money is never created or destroyed inside the ledger; it only moves. Opening balances are funded by a special world account that is allowed to go negative, so even money entering the system is a balanced entry. ([ADR 0004](docs/adr/0004-a-world-account-funds-openings-and-may-go-negative.md))
- **Money is a `long` in integer minor units.** No floats and no BigDecimal: amounts are counts of cents, added with overflow checks, and the JSON wire format only ever carries integers. ([ADR 0002](docs/adr/0002-money-is-a-long-in-integer-minor-units.md))
- **Idempotent transfers.** The server stores the first outcome for each idempotency key and replays it on any retry, backed by a unique constraint in the database so the guarantee survives restarts. The client mints one key per intended transfer, and a retry reuses the frozen key and body, never the edited form. ([ADR 0006](docs/adr/0006-idempotency-key-replays-first-outcome.md), [ADR 0019](docs/adr/0019-one-key-per-intended-transfer.md))
- **Hand-written JSON and the JDK's built-in HTTP server.** The API surface is small and fixed, so the project parses and writes its JSON by hand and serves HTTP from the standard library on virtual threads, instead of taking on a framework. The choice was revisited after a hardening review and kept deliberately. ([ADR 0008](docs/adr/0008-hand-written-json.md), [ADR 0009](docs/adr/0009-http-jdk-server.md), [ADR 0017](docs/adr/0017-keep-the-hand-written-json.md))
- **Postgres as the store, with the constraints as the last line of defense.** Plain SQL migrations run by a small hand-rolled runner, a fixed-size connection pool, pessimistic row locks at read committed, and stored balances with keyset pagination for statements. The invariants the code enforces are also declared as database constraints, so a bug in one layer is caught by another. ([ADR 0011](docs/adr/0011-pessimistic-row-locks-at-read-committed.md), [ADR 0012](docs/adr/0012-plain-sql-migrations-with-a-hand-rolled-runner.md), [ADR 0013](docs/adr/0013-a-hand-rolled-fixed-size-connection-pool.md), [ADR 0014](docs/adr/0014-stored-balances-and-keyset-pagination.md))
- **A client with two runtime dependencies.** The web app is React, TypeScript, and Vite, and nothing else: no router, no state library, no CSS framework, one hand-written stylesheet. The money, error, and transfer-intent logic are pure modules with their own tests. ([ADR 0018](docs/adr/0018-react-typescript-vite.md))
- **A static bearer token, checked at the edge.** Every endpoint except `/health` requires a token from the environment, reads included. This is still demo-grade auth on purpose, and the ADR names exactly what it does not protect against. ([ADR 0015](docs/adr/0015-a-static-bearer-token-checked-at-the-edge.md))
- **A throttle, browser headers, and signed cursors at the edge.** The kernel rate-limits by client address, with a small separate budget for failed authentication, and answers `429` with `Retry-After`. Every response carries a CSP written against the real Vite build plus the usual hardening headers, statement cursors are HMAC-signed so they cannot be forged, and no credential is a literal anywhere in the repository. ([ADR 0021](docs/adr/0021-rate-limits-security-headers-and-signed-cursors.md))

- **Fraud scoring after commit, never in the way.** Postings are scored asynchronously by deterministic rules that read each account's history back from the score store, and an offline reflection step can propose new rules that a replay gate accepts or rejects. ([ADR 0024](docs/adr/0024-asynchronous-fraud-scoring.md), [ADR 0025](docs/adr/0025-reflection-proposes-rules-a-gate-decides.md))
- **Kubernetes and observability without new libraries.** Plain manifests with Kustomize and kind, a migration Job, hand-written Prometheus metrics and JSON log lines. ([ADR 0022](docs/adr/0022-kubernetes-layout.md), [ADR 0023](docs/adr/0023-hand-rolled-metrics-and-json-logs.md))

How the guarantees are tested and where each one is enforced is written up in [docs/correctness.md](docs/correctness.md).

## Running it

The quickest way is Docker, which brings up Postgres and the app (API plus the built web client) on one origin. Both credentials come from a `.env` you create; nothing is hardcoded, and compose refuses to start without them:

```
cp .env.example .env            # then fill in the two values, e.g.
                                #   POSTGRES_PASSWORD=$(openssl rand -base64 24)
                                #   TALLY_API_TOKEN=$(openssl rand -hex 24)
docker compose up --build
```

Then open http://localhost:8080. `TALLY_API_TOKEN` is baked into the served JavaScript so creates and transfers work out of the box, which is exactly why it should be a throwaway local value: anyone who opens the page has it. `.env` is gitignored.

To run the backend by hand you need JDK 25. Maven comes from the wrapper, downloaded and checksum-verified on first use:

```
./mvnw package                # .\mvnw.cmd on Windows
TALLY_API_TOKEN=some-local-token-16ch java -jar target/tally.jar
```

The token is required (startup fails without one of at least 16 characters) and every endpoint but `GET /health` needs it, so a `curl` to the API carries `-H "Authorization: Bearer $TALLY_API_TOKEN"`. `TALLY_DB_URL` points at Postgres; leave it unset and the server runs on an in-memory store, which is enough to try the API. `TALLY_PORT` defaults to 8080.

For the web client in development:

```
cd web
npm install
npm run dev
```

Copy `web/.env.example` to `web/.env.local` and set `VITE_API_TOKEN` to the backend's token. The dev server runs on http://localhost:5173 and proxies API calls to the backend on 8080.

## Running it on Kubernetes

The same image runs on a local [kind](https://kind.sigs.k8s.io/) cluster, with Postgres, Prometheus and Grafana beside it. You need Docker, kind and kubectl, and the same `.env` as for compose:

```
make k8s-up                     # .\scripts\k8s-up.ps1 on Windows
make k8s-gates                  # the four deployment checks CI runs
make k8s-down                   # deletes the cluster and the database volume
```

`k8s-up` creates a one-node cluster named `tally`, builds the image, loads it into the node, creates the Secret `tally-secrets` from `.env`, applies `deploy/k8s/overlays/local`, and waits for Postgres, the migration Job, the API, Prometheus and Grafana. Then:

- http://localhost:8080 is the app, through a NodePort.
- http://localhost:3000 is Grafana. The "Tally" dashboard opens as the home page, for an anonymous viewer.
- http://localhost:9090 is Prometheus.

What is in the cluster, all in `deploy/k8s/base`: Postgres as a StatefulSet with a volume claim, a Job that runs the migrations, the API as a Deployment of two replicas with readiness and liveness probes on `/health`, requests and limits on every container, a PodDisruptionBudget, and a HorizontalPodAutoscaler on CPU. Each API pod has an init container that waits until the Job has applied every migration, so no pod serves an old schema. The HPA needs metrics-server, which kind does not ship; run `WITH_METRICS_SERVER=1 make k8s-up` to install it. No secret is in a manifest. [ADR 0022](docs/adr/0022-kubernetes-layout.md) has the reasoning.

`GET /metrics` serves Prometheus text behind the same bearer token: requests and latency per route template, transfers by outcome (`applied`, `replayed`, `rejected`), reconciliation duration, connection-pool wait, and rate-limit rejections. The metrics and the text format are written by hand, like the JSON. With `TALLY_LOG_FORMAT=json`, which the cluster sets, every log line is one JSON object, and its `requestId` is the value of the `X-Request-Id` response header. [ADR 0023](docs/adr/0023-hand-rolled-metrics-and-json-logs.md) covers both.

What has run where. The machine this was written on has no Docker, so the cluster itself has not been started there. What ran locally: both overlays render with `kubectl kustomize` and pass `kubeconform -strict`, the scripts pass `bash -n` and the PowerShell parser, and a jar on the in-memory store served `/metrics` that `prometheus_client`'s parser accepts, with every log line valid JSON. The `k8s` job in `.github/workflows/ci.yml` is the proof for the rest. It runs `scripts/k8s-up.sh` on a fresh kind cluster, then `scripts/k8s-gates.sh`, which checks four things in the style of FDE-Bench: the image builds and is on the node, every workload becomes ready, a transfer and its retry return the right balances through the NodePort and show up in Prometheus, and the live objects match the overlay with probes, limits and secret references in place. Then it runs the integration suite against the Postgres inside the cluster.

## Fraud scoring

Every applied transfer is scored for fraud after it commits, off the request path. The transfers handler puts the posting on a bounded in-memory queue and returns; one consumer thread scores it with five deterministic rules over the paying account's recent history (velocity, amount far above the account's own median, new payee, round amount, unusual hour), and stores the score and the rules that fired in `posting_scores`, keyed on the posting id so a posting delivered twice is scored once. A full queue drops the score, never the transfer. `TransferPathIndependenceTest` runs transfers against a scorer that never returns, one that takes a second per score, one that throws, and a full queue, and every transfer still answers in well under the scorer's time. `GET /accounts/{id}/risk` returns the latest scores, and the account panel in the web client shows them with the rules in plain words. The design is in [ADR 0024](docs/adr/0024-asynchronous-fraud-scoring.md).

The rules are measured offline on a seeded synthetic dataset, never real data:

```
python data/fraud/generate.py          # regenerates data/fraud/ byte for byte (seed 20260925)
./mvnw package
python eval/fraud/run.py               # writes eval/fraud/results.json and results.md
```

The dataset is 9,290 transfers over 30 days between 356 accounts, with four fraud patterns injected in the last ten days: bursts, structuring under a 10,000.00 line, account takeover at an unusual hour, and mule chains. The evaluation starts the jar on the in-memory store, replays every transfer through the real HTTP API, waits for the scorer to drain, and reads every score back through the risk endpoint. It needs a replay clock (`TALLY_FRAUD_REPLAY_CLOCK=true`) so the rules see the dataset's times instead of the replay's, and a raised rate limit; neither is set anywhere else. Two runs give the same numbers.

Results at the flag line of 40 points, from `eval/fraud/results.json`. 240 of 8,991 normal postings are flagged, a false positive rate of 0.0267. Precision for a pattern counts its flagged postings against all of those false positives. Latency is how many fraud postings in an episode went by before the first flagged one.

| Pattern | Postings | Precision | Recall | AUROC | Episodes detected | Latency mean | Latency median |
|---|---|---|---|---|---|---|---|
| burst | 127 | 0.0204 | 0.0394 | 0.8722 | 5 of 15 | 5.6 | 4 |
| structuring | 78 | 0.2453 | 1.0000 | 0.9933 | 15 of 15 | 0 | 0 |
| account_takeover | 25 | 0.0698 | 0.7200 | 0.9906 | 15 of 15 | 0 | 0 |
| mule_chain | 69 | 0.1045 | 0.4058 | 0.8370 | 15 of 15 | 0 | 0 |
| all fraud | 299 | 0.3496 | 0.4314 | 0.9055 | 50 of 60 | 0.56 | 0 |

What this says. Structuring and account takeover are caught early in every episode. Bursts are the weak spot: velocity alone stays under the flag line by design, and the payee is only new on the first payment of a burst, so recall is 0.0394 even though the ranking is decent (AUROC 0.8722). Every mule chain is caught at its first hop, the victim's own payment, but only 28 of the 69 hops are flagged: a mule's forwarding looks ordinary to rules that only read the payer's own outgoing history. Precision is low across the board: 240 normal postings are flagged against 129 fraud postings. These are the numbers the baseline earned, not tuned ones.

**Reflection.** `eval/fraud/reflect.py` is an offline step in the style of SR-Fraud. It shows a local model (`qwen/qwen3.5-9b` through LM Studio) the missed fraud and the false positives as per-feature distributions, and asks for one new rule as a JSON object limited to a fixed feature catalog. A gate then replays the whole dataset with the rule loaded and accepts it only if recall rises, AUROC does not fall, and no more normal postings are flagged. Before asking, the script checks that its evidence recomputes all 9,290 Java scores exactly. The recorded run made three proposals against a baseline of recall 0.4314, AUROC 0.9055 and 240 flagged normal postings:

- `incoming_60m_minor >= 1000` for 40 points: recall 0.5686, AUROC 0.9307, flagged normal postings 443. Rejected.
- `passthrough_pct >= 80` for 20 points: recall 0.5217, AUROC 0.9334, flagged normal postings 262. Rejected.
- `distinct_payees_60m >= 3` for 20 points: recall 0.5485, AUROC 0.9107, flagged normal postings 278. Rejected.

All three found real signal and all three cost false positives, so none was accepted and `eval/fraud/accepted-rules.json` is empty. Every proposal, the model's reason and the verdict are in `eval/fraud/reflection.json`. An accepted rule would only take effect when an operator sets `TALLY_FRAUD_EXTRA_RULES` to that file. [ADR 0025](docs/adr/0025-reflection-proposes-rules-a-gate-decides.md) has the design.

What ran where. The evaluation and the reflection ran on the in-memory store. The Postgres score store passes the same contract tests as the in-memory one, run locally against PostgreSQL 16.2 from the `pgserver` wheel (CI uses Postgres 17), but the evaluation itself has not been run against Postgres.

## Tests

```
./mvnw test                     # backend unit tests, no database needed

docker compose up -d db         # needs .env, and publishes only on 127.0.0.1
                                # then the integration suite against real Postgres:
TALLY_TEST_DB_URL="jdbc:postgresql://localhost:5432/tally_test?user=tally&password=$POSTGRES_PASSWORD" ./mvnw -Pintegration verify

cd web
npm test                        # vitest over the pure client modules
npm run typecheck
```

Without `TALLY_TEST_DB_URL` the integration tests skip with instructions rather than fail.

CI runs the same thing on every push: the backend job against a real Postgres service container, and the web job in parallel.

## Layout

- `src/` is the Java backend: the pure ledger domain, the in-memory and Postgres stores, and the HTTP layer.
- `web/` is the React client.
- `db/migrations/` holds the plain SQL schema migrations.
- `data/fraud/` holds the synthetic dataset and its generator, and `eval/fraud/` the evaluation, the reflection step and their results.
- `deploy/k8s/` holds the Kubernetes manifests, the kind config and the Grafana dashboard. `scripts/` holds the cluster scripts.
- `docs/` holds the ADRs and the correctness write-up.

## License

MIT, see [LICENSE](LICENSE). The Maven wrapper scripts keep their own Apache-2.0 headers.
