# :runtime

`DatabaseDriver` interface, `EntitySchema`/`ColumnMetadata`/`EdgeMetadata`,
query `Predicate` hierarchy, `Op` enum.

## Package layout

Runtime types are grouped by concern under `entkt.runtime.*`:

| Subpackage | Holds |
|---|---|
| `entkt.runtime.driver` | `DatabaseDriver` SPI, `NoopDriver`, `DriverTransactionResult`, and the schema metadata it consumes (`EntitySchema`, `ColumnMetadata`, `JsonColumnMetadata`, `ForeignKeyRef`, `IndexMetadata`, `EdgeMetadata`, `IdStrategy`) |
| `entkt.runtime.privacy` | `Viewer`, `PrivacyContext`, shared `PrivacyRuleContext`, scalar/batch privacy rules and evaluators, `allowAll`, `EntityPolicy` |
| `entkt.runtime.validation` | Shared `ValidationRuleContext` plus scalar/batch validation rules and evaluators |
| `entkt.runtime.hook` | Scalar/batch lifecycle hook contracts and factories |
| `entkt.runtime.rule` | Immutable `RuleBatch` inputs and read-only, same-batch `RuleDecisions` outputs for privacy and validation |
| `entkt.runtime.query` | interceptors (`QueryInterceptor`, `InterceptScope`, `ReadOperation`, …), aggregate types, `ExcludeDeleted` |
| `entkt.runtime.mutation` | `FieldPatch`, edge ops (`PendingEdgeOps`, `EdgeChanges`), `UpdateConsistency`/`RelationshipLocking`, `TransactionRequirement` |
| `entkt.runtime.result` | `ReadResult`/`MutationResult`/`TransactionResult` (+ `getOrThrow`/`visibleOrNull` projections), `TransactionScope`/`TransactionCoordinator`/`runEntTransaction`, `MutationWriteState`/`TransactionFailureState`, the denial payload types (`EntityKey`, `PrivacyDenial`, `LoadDenialOrigin`), and the `EntException`/`EntMutationException` typed-exception family |

## DatabaseDriver interface

```kotlin
interface DatabaseDriver {
    fun registerAll(schemas: List<EntitySchema>)
    fun register(schema: EntitySchema)
    fun registeredIdColumn(table: String): String
    fun <T> copyJsonValue(table: String, column: String, value: T): T
    fun insert(table: String, values: Map<String, Any?>): Map<String, Any?>
    fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>?
    fun byId(table: String, id: Any): Map<String, Any?>?
    fun query(
        table: String,
        predicates: List<Predicate<*>>,
        orderBy: List<OrderField<*>>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>>
    fun count(table: String, predicates: List<Predicate<*>>): Long
    fun exists(table: String, predicates: List<Predicate<*>>): Boolean
    fun delete(table: String, id: Any): Boolean
    fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>>
    fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate<*>>): Int
    fun deleteMany(table: String, predicates: List<Predicate<*>>): Int
    fun deleteManyByIds(
        table: String,
        idColumn: String,
        ids: List<Any>,
        predicates: List<Predicate<*>>,
    ): List<Any>
    fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T>
}
```

Rows are plain `Map<String, Any?>` keyed by snake_case column name — the
driver layer speaks in these maps and the generated entity classes provide
the typed facade. See [DatabaseDriver](../docs/10-drivers.md) for the complete SPI,
including lifecycle snapshot and logical-batch guarantees.

## Predicates

Sealed `Predicate` hierarchy —
`Leaf(field, op, value)`, `And`, `Or`, `HasEdge(edge)`, `HasEdgeWith(edge, inner)`.

**Ops:** `EQ`, `NEQ`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `NOT_IN`, `IS_NULL`,
`IS_NOT_NULL`, `CONTAINS`, `HAS_PREFIX`, `HAS_SUFFIX`.

## Transactions

`driver.withTransaction { txDriver -> ... }` runs a block inside a
transaction and reports the outcome structurally as
`DriverTransactionResult<T>`: `Success` only after a confirmed commit;
an ordinary block failure with a confirmed rollback is
`Failed(exception, NotCommitted)`; a failed rollback or commit is
`Failed(exception, OutcomeUnknown)`. `CancellationException` rethrows only
after confirmed rollback; commit-time cancellation or an unconfirmed rollback
is `Failed(cancellation, OutcomeUnknown)`. JVM `Error`s still rethrow. Calling
`withTransaction` again on the transaction-scoped driver throws
`NestedTransactionUnsupportedException`
before the nested block runs. Generated transaction clients omit the
nested entry point entirely. The client-level transaction boundary
(`runEntTransaction` + `TransactionScope.orRollback()`) builds on this
contract; see the operation-result-algebra design note.
