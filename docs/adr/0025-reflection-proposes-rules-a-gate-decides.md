# Reflection proposes rules, a deterministic gate decides

## Context

The five baseline rules (ADR-0024) miss most burst postings and more than half of the mule chain
hops in the offline evaluation. Tuning them by hand against the same numbers that judge them is how
a rule set quietly overfits. SR-Fraud (arXiv 2609.27287) describes a way to keep the scorer fixed
at request time while still learning: an offline agent reads matured errors and proposes a boundary,
and a deterministic check decides whether it is kept.

## Decision

**The rule language is closed.** A proposed rule is one JSON object: a name, one feature from a
fixed catalog, a comparison (`>=`, `<=` or `==`), an integer threshold, and points from 1 to 40.
The catalog is ten integer features the Java scorer already computes from the window, such as
`outgoing_10m`, `distinct_payees_60m` and `passthrough_pct`. The service loads such rules from the
file named by `TALLY_FRAUD_EXTRA_RULES` and refuses to start on a malformed one. The model cannot
write code, only fill in these five fields.

**The reflection step runs offline.** `eval/fraud/reflect.py` runs the evaluation, collects the
false negatives and false positives at the flag line, and summarises each feature's distribution for
missed fraud against normal traffic. It sends that evidence to a local model through LM Studio's
OpenAI-compatible API and asks for one rule.

**The gate is the evaluation itself.** The script writes the proposed rule to a file, starts a fresh
jar with it, replays the dataset, and accepts the rule only if recall over all fraud goes up, AUROC
over all fraud does not go down, and the number of flagged normal postings does not go up. Anything
else is rejected. Every proposal is logged with the model's reason, the numbers before and after,
and the verdict, in `eval/fraud/reflection.json`, accepted or not.

**Holdout.** An accepted rule is also scored on a second dataset from a different seed, which
played no part in the proposal or the gate. That number is reported next to the gate's.

**Acceptance is not deployment.** Accepted rules go to `eval/fraud/accepted-rules.json`. The service
only uses them when an operator points `TALLY_FRAUD_EXTRA_RULES` at that file.

## Alternatives

Let the model write Java or a free-form expression. More expressive, and impossible to check
cheaply for safety or determinism.

Call the model at request time. That puts a slow, non-deterministic dependency on every posting,
which is the thing ADR-0024 exists to avoid.

Accept on recall alone. A rule that flags everything has perfect recall. The false positive
condition is what stops that.

## Consequences

The gate and the proposal read the same dataset, so an accepted rule can still overfit it. The
holdout number is there to show how much. The model's output varies run to run, so the log, not
the script, is the record of what was proposed. The evidence the model sees is computed in Python
from the dataset, and the script first checks that recomputing the baseline scores from that
evidence matches the Java scores posting for posting, so the model is not reasoning about numbers
the service would not produce.

## Status

Accepted.
