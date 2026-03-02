# Tally

A double-entry money-movement service. Accounts hold balances, and money moves
between them through balanced ledger entries that can never create or destroy value.

The name comes from the split tally stick, the medieval way of recording a debt
on two matching halves. It is the ancestor of double-entry bookkeeping.

Java 25, standard library first, test driven. A thin React client arrives at the end.

Status: build skeleton only, no domain code yet.

## Build

    ./mvnw test          # or .\mvnw.cmd test on Windows

Requires JDK 25. Maven itself is downloaded and checksum-verified by the wrapper.

## License

MIT. The Maven wrapper scripts keep their Apache-2.0 headers.
