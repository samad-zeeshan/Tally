#!/usr/bin/env bash
# Delete the kind cluster and everything in it, the Postgres volume included.
set -euo pipefail
kind delete cluster --name "${CLUSTER:-tally}"
