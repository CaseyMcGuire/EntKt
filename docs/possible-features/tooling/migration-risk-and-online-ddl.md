# RFC: Migration Risk And Online DDL

## Status

Possible future feature. This is not implemented.

## Summary

Replace the binary "safe versus manual" migration vocabulary with explicit
risk categories, and support opt-in PostgreSQL online DDL strategies.

An additive statement is not necessarily data-safe, non-blocking, or guaranteed
to succeed on a populated database. Migration plans should say which property
they mean.

## Motivation

The current planner automatically emits several additive operations, including
indexes and foreign keys. Those operations do not discard data, but they can:

- fail because existing rows violate a new constraint
- hold locks that block application writes
- perform a long table or index scan
- be invalid inside a transaction when an online variant is chosen
- leave cleanup work after an interrupted concurrent index build

Calling every additive operation "safe" understates operational risk. Calling
all of them "manual" would discard useful automation. A richer classification
keeps automation while making cost and responsibility explicit.

## Risk Dimensions

Each planned operation should report independent dimensions:

```kotlin
data class MigrationRisk(
    val dataLoss: RiskLevel,
    val existingDataValidation: RiskLevel,
    val writeBlocking: RiskLevel,
    val tableRewrite: RiskLevel,
    val transactionConstraint: TransactionConstraint,
)
```

Human-readable summaries can group common combinations:

- `AdditiveSafe`: no expected data loss, validation failure, rewrite, or
  meaningful write blocking
- `DataDependent`: can fail based on existing rows
- `Blocking`: can block writes or hold a strong lock
- `RewriteRequired`: may rewrite a large table
- `Destructive`: can discard data or remove enforcement
- `Manual`: EntKt cannot safely choose the required application strategy

One operation may carry several labels.

## Example Classification

| Operation | Data risk | Operational risk | Default posture |
| --- | --- | --- | --- |
| Create empty table | additive | brief catalog locks | auto |
| Add nullable column without default | additive | brief catalog lock | auto |
| Add unique index | existing duplicates may fail | index build and write blocking | explicit strategy |
| Add foreign key | invalid rows may fail | validation scan and lock | explicit strategy |
| Set `NOT NULL` | existing nulls may fail | scan and lock | staged/manual |
| Drop column/table | destructive | lock | manual |
| Change column type | transform/rewrite dependent | lock or rewrite | manual |

The table is conceptual; PostgreSQL-version-specific behavior belongs to the
dialect planner and its tested capability metadata.

## PostgreSQL Online Strategies

### Indexes

Offer an explicit strategy:

```kotlin
IndexBuildStrategy.Blocking
IndexBuildStrategy.Concurrent
```

`CREATE INDEX CONCURRENTLY` reduces write blocking but:

- cannot run inside an ordinary migration transaction
- can take longer
- can leave an invalid index after failure
- needs a separate cleanup and retry diagnostic

EntKt must not silently switch a Flyway migration to non-transactional mode.
The generated file and report must name the requirement.

### Foreign Keys

An online-style plan can stage a constraint:

```sql
ALTER TABLE child
ADD CONSTRAINT fk_child_parent
FOREIGN KEY (parent_id) REFERENCES parent(id)
NOT VALID;

ALTER TABLE child
VALIDATE CONSTRAINT fk_child_parent;
```

The first statement protects new writes while deferring validation of existing
rows. The second still performs a scan and needs its own operational warning.

### Not-Null Changes

Prefer a staged plan:

1. add a nullable column
2. deploy application writes for both old and new rows
3. backfill in application-controlled batches
4. validate a check constraint where appropriate
5. set `NOT NULL`

EntKt may describe this sequence but should not invent the backfill expression
or deploy ordering.

## Plan Versus Render

Migration planning returns structured operations and risks before SQL is
written. Rendering requires an explicit strategy for every operation whose
default is ambiguous:

```text
AddIndex users(email)
  risks: DataDependent, Blocking
  available strategies: Blocking, Concurrent
  selected strategy: none
```

CI can reject unacknowledged risk classes. Local generation may produce a
guarded checklist, but never a silently destructive or transaction-invalid
file.

## Data Validation

A shadow database proves that committed migrations create the desired schema;
it does not prove that a production data set satisfies a new unique or foreign
key constraint.

Plans should emit optional preflight SQL for operators to run against a target
environment, for example duplicate or orphan detection. EntKt must not execute
those checks against production without a separate, explicit command and
connection scope.

## Verification

Verification should distinguish:

- schema convergence in a fresh shadow database
- migration-file syntax and transaction compatibility
- acknowledged operational risk
- target-data validation, which is not proven by the shadow database

Reports and JSON output use the same structured risk model as generation.

## Non-Goals

- Do not apply migrations to production.
- Do not infer business backfill values.
- Do not claim zero downtime.
- Do not make concurrent DDL the implicit default.
- Do not collapse PostgreSQL-specific behavior into portable guarantees.
- Do not auto-drop an invalid index without naming the exact target and risk.

## Test Requirements

- every migration operation receives explicit risk dimensions
- additive-but-data-dependent operations are not labeled unconditionally safe
- blocking and concurrent index strategies render distinct valid files
- transaction-incompatible SQL is rejected or accompanied by explicit Flyway
  configuration metadata
- `NOT VALID` and validation phases remain separately visible
- shadow verification does not claim target-data validation
- JSON reports preserve stable risk codes and selected strategies
- invalid concurrent indexes produce actionable cleanup diagnostics
- destructive operations remain guarded and manual

## Related Features

- [Migration Diagnostics](migration-diagnostics.md)
- [Gradle Developer Experience](gradle-dx.md)
- [Driver Capability Matrix](driver-capability-matrix.md)
