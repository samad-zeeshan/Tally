#!/usr/bin/env bash
# The four deployment gates, after FDE-Bench (arXiv 2609.27571): the image builds, the pods become
# ready, a transfer round-trip behaves, and the deployed config conforms to the manifests. Run after
# k8s-up.sh against the same cluster. Exits non-zero on the first failed gate.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER="${CLUSTER:-tally}"
OVERLAY="${OVERLAY:-local}"
TAG="${TAG:-$OVERLAY}"
BASE="${BASE:-http://localhost:8080}"
PROM="${PROM:-http://localhost:9090}"
NS=tally

token="$(grep -E '^TALLY_API_TOKEN=' .env | head -n1 | cut -d= -f2-)"
auth=(-H "Authorization: Bearer $token")

pass() { echo "PASS  $*"; }
fail() { echo "FAIL  $*" >&2; exit 1; }
check() { local what="$1"; shift; if "$@"; then pass "$what"; else fail "$what"; fi; }

echo "== gate 1: the image builds and is on the node"
check "image tally:$TAG exists locally" docker image inspect "tally:$TAG" >/dev/null
check "image tally:$TAG is loaded into kind" \
  sh -c "docker exec $CLUSTER-control-plane crictl images | grep -q 'tally.*$TAG'"

echo "== gate 2: every workload is ready"
check "postgres ready" kubectl -n $NS rollout status statefulset/tally-db --timeout=180s
check "migration job completed" kubectl -n $NS wait --for=condition=complete job/tally-migrate --timeout=180s
check "api rollout complete" kubectl -n $NS rollout status deployment/tally-api --timeout=180s
check "every api pod ready" kubectl -n $NS wait --for=condition=ready pod -l app.kubernetes.io/name=tally-api --timeout=120s
check "prometheus ready" kubectl -n $NS rollout status deployment/prometheus --timeout=180s
check "grafana ready" kubectl -n $NS rollout status deployment/grafana --timeout=180s

echo "== gate 3: a transfer round-trip through the NodePort"
check "health answers" sh -c "curl -fsS $BASE/health | grep -q '\"ok\"'"
check "the web client loads" sh -c "curl -fsS $BASE/ | grep -q 'id=\"root\"'"
a="$(curl -fsS "${auth[@]}" -H 'Content-Type: application/json' -d '{"name":"Gate A","openingBalanceMinor":1000}' "$BASE/accounts" | jq -r .id)"
b="$(curl -fsS "${auth[@]}" -H 'Content-Type: application/json' -d '{"name":"Gate B"}' "$BASE/accounts" | jq -r .id)"
key="gate-$(date +%s)-$RANDOM"
body="{\"fromAccountId\":\"$a\",\"toAccountId\":\"$b\",\"amountMinor\":250}"
first="$(curl -sS -o /dev/null -w '%{http_code}' "${auth[@]}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $key" -d "$body" "$BASE/transfers")"
check "transfer applied with 201" test "$first" = 201
replay_headers="$(curl -sS -D - -o /dev/null "${auth[@]}" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $key" -d "$body" "$BASE/transfers")"
check "retry with the same key is a replay" sh -c "printf '%s' \"\$1\" | grep -qi '^Idempotency-Replayed: true'" _ "$replay_headers"
check "sender holds 750" test "$(curl -fsS "${auth[@]}" "$BASE/accounts/$a" | jq .balanceMinor)" = 750
check "receiver holds 250" test "$(curl -fsS "${auth[@]}" "$BASE/accounts/$b" | jq .balanceMinor)" = 250
recon="$(curl -fsS "${auth[@]}" "$BASE/reconciliation")"
check "reconciliation is consistent" test "$(echo "$recon" | jq .consistent)" = true
check "the book sums to zero" test "$(echo "$recon" | jq .globalSumMinor)" = 0

request_id="$(curl -sS -D - -o /dev/null "${auth[@]}" "$BASE/accounts" | tr -d '\r' | awk -F': ' 'tolower($1)=="x-request-id"{print $2}')"
check "the response carries X-Request-Id" test -n "$request_id"
found=""
for _ in $(seq 1 10); do
  if kubectl -n $NS logs -l app.kubernetes.io/name=tally-api -c api --tail=-1 | grep -F "\"requestId\":\"$request_id\"" | jq -e . >/dev/null 2>&1; then
    found=yes; break
  fi
  sleep 1
done
check "the same request id is in a JSON log line" test -n "$found"

# Anything read from Prometheus depends on a scrape having happened. The scrape interval is 15 seconds,
# and a pod that just became ready also waits for the next discovery refresh, so each such check polls
# for up to 90 seconds before it fails. A single read right after the rollout failed on timing alone.
prom_at_least() {
  local query="$1" want="$2" got=0
  for _ in $(seq 1 18); do
    got="$(curl -fsS "$PROM/api/v1/query" --data-urlencode "query=$query" 2>/dev/null \
      | jq -r '.data.result[0].value[1] // "0"' 2>/dev/null || echo 0)"
    [ "${got%.*}" -ge "$want" ] 2>/dev/null && return 0
    sleep 5
  done
  echo "      $query was ${got:-nothing} after 90 seconds, wanted at least $want" >&2
  return 1
}
check "prometheus sees the applied transfer" prom_at_least 'sum(tally_transfers_total{outcome="applied"})' 1
check "prometheus scrapes every api pod" prom_at_least 'count(up{job="tally-api"} == 1)' 2

echo "== gate 4: the deployed config conforms to the manifests"
# kubectl diff exits 1 when the live objects differ from what the overlay renders.
check "no drift between the overlay and the cluster" kubectl diff -k "deploy/k8s/overlays/$OVERLAY" >/dev/null
deploy="$(kubectl -n $NS get deployment tally-api -o json)"
check "every api container has cpu and memory requests and limits" test "$(echo "$deploy" | jq '
  [.spec.template.spec.containers[], .spec.template.spec.initContainers[]]
  | all(.resources.requests.cpu and .resources.requests.memory and .resources.limits.cpu and .resources.limits.memory)')" = true
check "readiness and liveness probes hit /health" test "$(echo "$deploy" | jq '
  .spec.template.spec.containers[0] | .readinessProbe.httpGet.path == "/health" and .livenessProbe.httpGet.path == "/health"')" = true
check "both secrets come from tally-secrets" test "$(echo "$deploy" | jq '
  [.spec.template.spec.containers[0].env[] | select(.name == "TALLY_API_TOKEN" or .name == "TALLY_DB_PASSWORD")
   | .valueFrom.secretKeyRef.name == "tally-secrets"] | length == 2 and all')" = true
check "no container carries a literal secret value" test "$(echo "$deploy" | jq '
  [.spec.template.spec.containers[].env[]? | select(.name | test("TOKEN|PASSWORD")) | has("value")] | any | not')" = true
check "the live pod runs with json logs" test "$(kubectl -n $NS exec deploy/tally-api -c api -- printenv TALLY_LOG_FORMAT)" = json
check "the live pod leaves migrations to the job" test "$(kubectl -n $NS exec deploy/tally-api -c api -- printenv TALLY_MIGRATE_ON_START)" = false
check "the pod disruption budget exists" kubectl -n $NS get pdb tally-api
check "the autoscaler targets the api on cpu" test "$(kubectl -n $NS get hpa tally-api -o json | jq -r '.spec.metrics[0].resource.name')" = cpu
check "every postgres and monitoring container has limits" test "$(kubectl -n $NS get statefulset,deployment -o json | jq '
  [.items[].spec.template.spec.containers[] | .resources.limits.cpu and .resources.limits.memory] | all')" = true

echo
echo "all four gates passed"
