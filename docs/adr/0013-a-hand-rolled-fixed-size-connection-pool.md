# A hand-rolled fixed-size connection pool

## Context

A new connection per request costs a TCP and auth handshake each, and on a virtual-thread-per-request
server under load they stampede toward Postgres `max_connections` (100 by default). Something has to
bound the number of open connections.

## Decision

A fixed-size pool on an `ArrayBlockingQueue`, filled eagerly at startup. Borrow polls with a timeout
and validates the connection with `isValid(1)` before handing it out; a dead one is discarded and
replaced with a fresh connection. Give-back rolls back a leaked open transaction and resets autocommit,
and replaces a broken connection so the pool never shrinks. About sixty lines of standard library.

## Alternatives

`DriverManager.getConnection` per request. Unbounded connection growth under load and a handshake on
every call.

## Consequences

The pool bounds database load no matter how many virtual threads exist, and because JEP 491 (JDK 24,
present in Java 25) removed synchronized pinning, a virtual thread that blocks borrowing does not pin
its carrier. Validate-on-borrow means a database bounce, which drops every socket, heals on the next
borrow rather than handing out a dead connection. What it deliberately lacks: leak detection, metrics,
and adaptive sizing. HikariCP is what a production service would use, and the reason it is not here is
that a validated fixed pool is enough to demonstrate the concern and keep the primitive visible.

## Status

Accepted.
