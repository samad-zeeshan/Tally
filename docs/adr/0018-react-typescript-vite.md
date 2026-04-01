# React, TypeScript, Vite, and vitest for the client

## Context

The service needs a browser client that can create accounts, show balances and statements, and make
transfers, and it has to carry the end-to-end idempotency story convincingly. The client also has to be
a credible front-end sample for the roles this project targets.

## Decision

React 19 with TypeScript 6, built by Vite 8, tested by vitest 4 (versions as resolved at build time). The
UI is three panels sharing a little state (the selected account, a refresh after a transfer), which is
exactly React's component-plus-state model, and React is the front-end skill the target roles list.
TypeScript makes the REST contract checked types, so a renamed field is a compile error and the
money-as-integer rule can be policed at the type boundary. Vite's dev-server proxy is what lets the
client avoid CORS entirely, and its build output is plain static files, which is what the container
deployment wants. vitest runs the TypeScript through the same Vite pipeline with no extra transform
config; its scope here is deliberately narrow, the three pure modules that carry the guarantee, no
component or DOM testing.

No router: one page with sections and an App-level `useState` for the selected account, so a router
would be the largest dependency in the client to serve zero requirements. No ESLint: in a client this
size `tsc -b` and the vitest suite carry the checks, and an unconfigured linter is dead weight. No CORS
code in the Java server: in dev the browser only talks to the Vite origin and the proxy forwards
server-side, and in the container the client and API are served same-origin, so a cross-origin request
never exists. The implication, correct for a service that is not a public API, is that it cannot be
called from other web origins.

## Alternatives

Vanilla TypeScript DOM manipulation: the primitives-first point was already made in the Java backend, so
repeating it in the browser buys nothing. Vue or Svelte: fine tools, weaker fit for the target roles.
Create React App: deprecated. Jest: needs its own TypeScript wiring that duplicates what Vite already
does. react-router: a dependency for deep-linking a single page has nothing to link.

## Consequences

The client is a separate npm project under `web/` with its own lockfile and toolchain, independent of
the Maven build until Stage 8 wires both into CI and the container. `node_modules` under OneDrive needs
sync paused during install. Nothing here touches the Java backend.

## Status

Accepted.
