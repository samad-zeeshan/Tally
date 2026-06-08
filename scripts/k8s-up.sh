#!/usr/bin/env bash
# Bring Tally up on a local kind cluster: build the image, load it, create the Secret from .env, apply
# the overlay, wait for every piece, and print the URLs. Safe to run again. See ADR-0022.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER="${CLUSTER:-tally}"
OVERLAY="${OVERLAY:-local}"
TAG="${TAG:-$OVERLAY}"

for tool in docker kind kubectl; do
  command -v "$tool" >/dev/null || { echo "k8s-up: $tool is required" >&2; exit 1; }
done
[ -f .env ] || { echo "k8s-up: .env is missing, copy .env.example to .env and fill it in" >&2; exit 1; }

# Read the token with grep rather than sourcing .env, so a stray line in it is never executed.
token="$(grep -E '^TALLY_API_TOKEN=' .env | head -n1 | cut -d= -f2-)"
password="$(grep -E '^POSTGRES_PASSWORD=' .env | head -n1 | cut -d= -f2-)"
if [ -z "$token" ] || [ -z "$password" ]; then
  echo "k8s-up: set both TALLY_API_TOKEN and POSTGRES_PASSWORD in .env" >&2
  exit 1
fi

if ! kind get clusters | grep -qx "$CLUSTER"; then
  kind create cluster --name "$CLUSTER" --config "${KIND_CONFIG:-deploy/k8s/kind/cluster.yaml}"
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

docker build --build-arg VITE_API_TOKEN="$token" -t "tally:$TAG" .
kind load docker-image "tally:$TAG" --name "$CLUSTER"

kubectl create namespace tally --dry-run=client -o yaml | kubectl apply -f -
kubectl -n tally create secret generic tally-secrets --from-env-file=.env --dry-run=client -o yaml \
  | kubectl apply -f -

if [ "${WITH_METRICS_SERVER:-0}" = "1" ]; then
  kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
  # kind's kubelet serves a self-signed certificate, which metrics-server refuses by default.
  kubectl -n kube-system patch deployment metrics-server --type=json \
    -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' || true
fi

# A Job's pod template is immutable, so the old one goes first. It reruns on every up, which is safe
# because the runner skips files already recorded.
kubectl -n tally delete job tally-migrate --ignore-not-found
existed="$(kubectl -n tally get deployment tally-api --ignore-not-found -o name)"
kubectl apply -k "deploy/k8s/overlays/$OVERLAY"
# The tag does not change between builds, so a running Deployment would keep the old image without this.
if [ -n "$existed" ]; then
  kubectl -n tally rollout restart deployment/tally-api
fi

kubectl -n tally rollout status statefulset/tally-db --timeout=300s
kubectl -n tally wait --for=condition=complete job/tally-migrate --timeout=300s
kubectl -n tally rollout status deployment/tally-api --timeout=300s
for monitor in prometheus grafana; do
  if kubectl -n tally get deployment "$monitor" >/dev/null 2>&1; then
    kubectl -n tally rollout status "deployment/$monitor" --timeout=300s
  fi
done

echo
echo "Tally:      http://localhost:8080"
echo "Grafana:    http://localhost:3000   (anonymous viewer, dashboard \"Tally\")"
echo "Prometheus: http://localhost:9090"
