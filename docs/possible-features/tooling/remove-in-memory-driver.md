# RFC: Remove InMemoryDriver

## Status

**Implemented.** `InMemoryDriver` and its `LockSupportInMemoryDriver`
test wrapper have been removed. Integration tests were migrated to
`PostgresDriver` via the shared `PostgresTestBase` (Testcontainers,
`postgres:16-alpine`); tests that were redundant with existing
Postgres-backed coverage (`PrivacyIntegrationTest`,
`ValidationIntegrationTest`, `SqlstateConstraintMappingPostgresIntegrationTest`,
`LinkTableM2MPostgresIntegrationTest`) were deleted rather than
migrated. The `:example-demo` module was deleted; `:example-spring`
remains as the runnable Postgres-backed example.

## Summary

Remove `InMemoryDriver` as a supported runtime driver and make Postgres the
only first-party persistence implementation.

The current in-memory driver is useful for quick demos, but it has grown into a
large best-effort database simulator. It now has to mirror query predicates,
edge traversal, constraints, referential actions, transactions, error
classification, query explanation, and parts of the generated API surface. That
scope is expensive and still cannot model the driver behavior that matters most
for production: SQL rendering, database constraints, isolation, locking, and
concurrency.

## Motivation

`InMemoryDriver` started as a lightweight way to validate generated APIs before
a SQL backend existed. That is no longer the shape of the project. The Postgres
driver exists, owns the production semantics, and is already covered by
container-backed tests.

Keeping `InMemoryDriver` creates several costs:

- It encourages tests to pass against semantics that are not Postgres.
- It adds a second implementation for every driver contract change.
- It requires fake or partial behavior for transactions and locking.
- It makes docs describe two first-party drivers even though only one can be a
  production source of truth.
- It gives new features an easy fast-test path that can miss SQL, constraint,
  and concurrency bugs.

The strongest signal is the locking surface: `InMemoryDriver` correctly reports
that it does not support true row locking or owner-edge serialization, while
tests use `LockSupportInMemoryDriver` to advertise those capabilities without
real lock semantics. That is useful for exercising code paths, but it is also a
sign that the in-memory backend is being asked to prove behavior it cannot
actually provide.

## Goals

- Remove `InMemoryDriver` from public runtime docs and examples.
- Move generated API integration coverage to Postgres-backed tests.
- Keep narrow test doubles only where they test generated control flow without
  pretending to be a database.
- Make `PostgresDriver` the only first-party driver that defines persistence
  semantics.
- Reduce future feature work by avoiding duplicate driver implementation.

## Non-Goals

- Do not remove the `Driver` interface.
- Do not remove support for third-party drivers.
- Do not add another embedded database dependency as part of this cleanup.
- Do not replace every in-memory test with a slower end-to-end test when a
  focused unit test or generated-code test double is enough.
- Do not preserve source compatibility for `InMemoryDriver`; this is a
  greenfield project and can take breaking changes.

## Proposed Changes

1. Delete `runtime/src/main/kotlin/entkt/runtime/InMemoryDriver.kt`.
2. Delete `runtime/src/test/kotlin/entkt/runtime/InMemoryDriver*Test.kt`.
3. Delete `integration-tests/src/test/kotlin/entkt/integrationtest/support/LockSupportInMemoryDriver.kt`.
4. Replace generated integration tests that currently instantiate
   `InMemoryDriver`.
5. Update docs and examples to construct `PostgresDriver`.
6. Remove `:example-demo` or convert it to a Postgres-backed demo.

Replacement tests should use Postgres-backed integration tests when behavior
depends on driver semantics. Use narrow fake `Driver` implementations local to
the test when behavior is purely generated control flow. Use codegen golden
tests when the assertion is about generated source shape.

## Test Migration Strategy

Use the behavior under test to decide the replacement:

- **Driver semantics:** run against `PostgresDriver`. This includes predicates,
  ordering, constraints, referential actions, transactions, locking,
  classification of database errors, and query explanations.
- **Generated public API behavior:** prefer Postgres if the test crosses a real
  persistence boundary. Use a local fake driver only when the test asserts that
  generated code calls the driver with the expected shape.
- **Privacy, hooks, validation, and result variants:** keep integration coverage
  on Postgres for at least one representative path per operation. Use unit tests
  for pure policy/validation functions where possible.
- **Capability gates:** test with small local driver stubs that explicitly
  advertise the relevant capability flags. Do not use those stubs to claim
  concurrency correctness.
- **Concurrency and locking:** Postgres only.

This intentionally makes some tests slower. The tradeoff is that failures become
more meaningful because the suite exercises the same semantics users will run.

## Migration Order

1. Update docs to mark `InMemoryDriver` as deprecated or removal-bound.
2. Convert `example-demo` and getting-started snippets away from
   `InMemoryDriver`.
3. Move or rewrite integration tests that depend on `InMemoryDriver`.
4. Delete `LockSupportInMemoryDriver`.
5. Delete `InMemoryDriver` and its runtime tests.
6. Remove remaining docs references and update module READMEs.

Do not start by deleting the driver. The useful first step is to stop adding new
tests and docs that depend on it, then migrate existing coverage by category.

## Impacted Areas

- `runtime`: removes the concrete in-memory implementation and tests.
- `integration-tests`: replaces many `InMemoryDriver` fixtures with Postgres or
  narrow local fakes.
- `example-demo`: either removed or made Postgres-backed.
- Docs: `README.md`, `docs/01-getting-started.md`, `docs/03-edges.md`,
  `docs/10-drivers.md`, and `runtime/README.md` should stop presenting
  `InMemoryDriver` as a supported path.
- Future RFCs: any proposal that depends on an in-memory generated integration
  suite should be revised to use Postgres or test doubles.

## Alternatives Considered

### Keep It But Demote It

Keep `InMemoryDriver` as a documented best-effort test driver and stop calling
it a parity oracle.

This is lower risk in the short term, but it still leaves a large public
implementation that feature work has to maintain. It also keeps the tempting
fast path that can hide Postgres-specific bugs.

### Move It To Test Fixtures

Move the driver out of `:runtime` into a test-only module.

This reduces public API pressure, but it still leaves the database simulator to
maintain. It may be a useful interim step if deleting all in-memory tests at
once is too large.

### Replace It With H2 Or SQLite

Use an embedded database for faster tests.

This changes the problem rather than solving it. H2 or SQLite would still have
different SQL, constraint, isolation, and locking behavior from Postgres. If
driver semantics matter, tests should use Postgres.

## Open Questions

- Should `example-demo` be deleted, moved under `example-spring`, or converted
  into a small Postgres CLI demo?
- Do we want a dedicated `test-support` module for narrow fake `Driver`
  implementations?
- Should deprecation happen in one commit before deletion, or is direct removal
  acceptable while the project is still greenfield?

## Test Requirements

Before removing `InMemoryDriver`, add or preserve tests for:

- generated CRUD paths against `PostgresDriver`
- predicate, ordering, limit, and offset behavior against `PostgresDriver`
- constraint and referential-action behavior against `PostgresDriver`
- transaction requirement and capability-gate behavior with local driver stubs
- result variant mapping for Postgres constraint errors and generic driver
  failures
- docs examples compiling or being exercised by the relevant example module
