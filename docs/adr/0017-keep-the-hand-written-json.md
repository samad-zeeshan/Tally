# Keep the hand-written JSON past the hardening review

## Context

By this stage a real JSON library would be a defensible dependency, so the choice to keep the
hand-rolled reader and writer should be a deliberate revisit with a recorded trigger, not a default by
inertia. ADR-0008 chose hand-written JSON for a small fixed surface; this re-examines that now that the
surface is larger and auth, validation, and an envelope have been added.

## Decision

Keep the hand-rolled reader and writer. The surface is still six endpoints and about five flat
request and response shapes, well within what a few hundred tested lines handle. The reader is itself a
showcase piece, and it enforces a money rule a databind library makes awkward: it rejects a fractional
or exponent `amountMinor` at parse time, so no float ever touches money, before validation even runs.
Adopting Jackson would introduce the project's first reflection-driven dependency exactly where the
"primitives stay visible" philosophy pays off.

## Alternatives

Jackson databind: capable and standard, and it would erase the per-field hand-mapping. Rejected because
it replaces a deliberate showcase with reflection magic and pulls in a dependency the surface does not
justify.

## Consequences

Every new field is hand-mapped, and nested structures beyond one level would get painful. Recorded
revisit trigger: adopt a real library when the API grows past roughly ten distinct body shapes, needs
nesting more than one level deep, or gains a consumer that requires strict JSON edge-case conformance
beyond the tested subset.

## Status

Accepted.
