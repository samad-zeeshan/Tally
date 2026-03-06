# Hand-written JSON, integers only, for a small fixed surface

## Context

The API is four endpoints with flat request bodies, and money is integer minor units. The
serialization is small enough to understand rather than pull in as a black box, and it is a
place a money rule can be enforced at the parse boundary.

## Decision

A minimal RFC 8259 subset in `dev.tally.json`: a sealed `JsonValue` hierarchy of records, a
recursive-descent parser with the scanner fused in, and a compact deterministic writer. Three
deliberate deviations from common libraries: numbers parse to `long` only and any fraction or
exponent is a parse error; duplicate object keys are rejected; nesting is capped at depth 64.
Errors carry a 1-based line and column.

## Alternatives

Jackson or Gson. Capable and standard, but they hide exactly the mechanics worth showing and
pull in a dependency the surface does not need.

Parsing numbers as `double` or `BigDecimal`. Lets floating point near money, and no endpoint
needs a fraction.

Last-wins duplicate keys, which most libraries do silently. In a money API,
`{"amountMinor":1,"amountMinor":1000000}` resolved by parser whim is an attack surface.

A separate tokenizer class. A layer and an allocation per token with no payoff at this size.

## Consequences

`{"amountMinor":1.5}` fails as a parse error before any validation runs, which is the intended
hard line: a fractional amount cannot even reach the handler. The library cannot represent
arbitrary third-party JSON, which is fine because the service only reads its own API bodies. The
trigger to revisit is the surface growing past what a page of grammar covers.

## Status

Accepted.
