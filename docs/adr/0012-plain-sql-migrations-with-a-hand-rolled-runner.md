# Plain SQL migrations with a hand-rolled runner

## Context

The schema has to be created reproducibly, on a developer machine and in CI, and it has to be
possible to add a change later without hand-editing a live database. This is a handful of files,
not hundreds.

## Decision

Plain `.sql` files under `db/migrations`, read from the filesystem via `TALLY_MIGRATIONS_DIR`, applied
once each in file-name order and recorded in a `schema_version` table, each in its own transaction. A
small `MigrationRunner` does it: create `schema_version` if absent, list `*.sql` sorted by name, skip
the ones already recorded, and for each new one run the whole file text and insert its name, then
commit.

## Alternatives

A migration framework, Flyway or Liquibase. A dependency the project does not need for a handful of
files, and it would hide the mechanism this project exists to show.

Classpath resources instead of the filesystem. Enumerating a resource directory inside a jar needs the
`FileSystems.newFileSystem` jar dance and teaches nothing about migrations; the container just copies
the directory in.

A hand-written SQL splitter. The pgJDBC driver runs a multi-statement string in one `execute`, so
splitting on semicolons, which breaks on a semicolon inside a string or a function body, is unnecessary.

## Consequences

Postgres DDL is transactional, so applying a file and recording it commit atomically: a failed
migration leaves no trace, not a half-created schema. There is no advisory lock around the run, which
is fine because there is one app process by design and CI runs it once. The runner is about fifty lines
of stdlib, and it runs at app startup and in the integration-test bootstrap.

## Status

Accepted.
