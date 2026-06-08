#!/usr/bin/env bash
# The fifth gate: switch the running cluster to the faults overlay, then run the fault harness against it.
# Run after k8s-up.sh. Exits non-zero if any fault run breaks an invariant.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER="${CLUSTER:-tally}"
OVERLAY="${OVERLAY:-local}"
TAG="${TAG:-$OVERLAY}"
BASE="${BASE:-http://localhost:8080}"
PYTHON="${PYTHON:-python3}"

docker tag "tally:$TAG" tally:faults
kind load docker-image tally:faults --name "$CLUSTER"
# A Job's pod template is immutable and the image tag changes here, so the old Job goes first. The
# migration runner skips files it has already recorded.
kubectl -n tally delete job tally-migrate --ignore-not-found
kubectl apply -k deploy/k8s/overlays/faults
kubectl -n tally rollout status deployment/tally-api --timeout=300s
# A rollout reports done once the new pods are ready, and the old ones may still hold the NodePort for a
# moment. Waiting for exactly two pods keeps the first requests off a terminating pod.
for _ in $(seq 1 60); do
  [ "$(kubectl -n tally get pods -l app.kubernetes.io/name=tally-api --no-headers | grep -c Running)" = 2 ] && break
  sleep 2
done

"$PYTHON" eval/faults/harness.py --base "$BASE" "$@"
