# Thin aliases for the scripts, which hold the real steps. On Windows run scripts/k8s-up.ps1 instead.
.PHONY: k8s-up k8s-down k8s-gates

k8s-up:
	./scripts/k8s-up.sh

k8s-down:
	./scripts/k8s-down.sh

k8s-gates:
	./scripts/k8s-gates.sh
