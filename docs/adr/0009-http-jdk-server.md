# HTTP on the JDK built-in server with a virtual-thread executor

## Context

The service needs HTTP without a framework, and its handlers are naturally blocking: read a
body, take the store's account locks, write a response. The primitives should stay visible.

## Decision

Serve on `com.sun.net.httpserver.HttpServer` with a single context at `"/"` and one
`HttpKernel` `HttpHandler` that delegates to an internal `Router`, a method-plus-template table
that captures `{id}` segments. Give the server `Executors.newVirtualThreadPerTaskExecutor()`,
one virtual thread per exchange.

## Alternatives

A fixed platform-thread pool. It reintroduces a tuning knob and a queueing failure mode this
workload does not need.

One `createContext` per path. Contexts do prefix matching, cannot express `{id}` templates, and
give no clean hook for a 405, so the behaviour would live half in JDK internals and half here.

A servlet container or a framework. Hides the primitives this project exists to show.

## Consequences

Handlers stay straight-line blocking code. Virtual threads are final since JDK 21 (JEP 444),
and as of Java 25 blocking on `synchronized` no longer pins the carrier thread (JEP 491,
delivered in JDK 24), so the store's locking is virtual-thread-safe unchanged.
`com.sun.net.httpserver` is a supported, exported API in the `jdk.httpserver` module, the same
one `jwebserver` (JEP 408) is built on, not a `sun.*` internal. Because the kernel owns `"/"`,
any later static-file or health serving must be a router route or a kernel fallback, not a
competing context.

## Status

Accepted.
