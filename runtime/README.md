# :runtime

`Driver` interface, `EntitySchema`/`ColumnMetadata`/`EdgeMetadata`,
query `Predicate` hierarchy, `Op` enum.

## Package layout

Runtime types are grouped by concern under `entkt.runtime.*`:

| Subpackage | Holds |
|---|---|
| `entkt.runtime.driver` | `Driver` SPI, `NoopDriver`, `classifyDriverError`, and the schema metadata it consumes (`EntitySchema`, `ColumnMetadata`, `JsonColumnMetadata`, `ForeignKeyRef`, `IndexMetadata`, `EdgeMetadata`, `IdStrategy`) |
| `entkt.runtime.privacy` | `Viewer`, `PrivacyContext`, `PrivacyDecision`/`PrivacyRule`, `PrivacyDeniedException`, `allowAll`, `EntityPolicy` |
| `entkt.runtime.validation` | `ValidationDecision`, `ValidationRule`, `ValidationException` |
| `entkt.runtime.query` | interceptors (`QueryInterceptor`, `InterceptScope`, `InterceptorEngine`, `ReadOperation`, …), `QueryPlan`/`QueryExplanation`, aggregate types, `ExcludeDeleted` |
| `entkt.runtime.mutation` | `FieldPatch`, edge ops (`PendingEdgeOps`, `EdgeChanges`), `UpdateConsistency`/`RelationshipLocking`, `TransactionRequirement` |
| `entkt.runtime.result` | `EntResult` (+ `map`/`flatMap`/`getOrThrow`), `EntResultScope`, `EntError`, and the `Ent*Exception` family |

## Driver interface

```kotlin
interface Driver {
    fun register(schema: EntitySchema)
    fun insert(table: String, values: Map<String, Any?>): Map<String, Any?>
    fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>?
    fun byId(table: String, id: Any): Map<String, Any?>?
    fun query(
        table: String,
        predicates: List<Predicate>,
        orderBy: List<OrderField>,
        limit: Int?,
        offset: Int?,
    ): List<Map<String, Any?>>
    fun count(table: String, predicates: List<Predicate>): Long
    fun exists(table: String, predicates: List<Predicate>): Boolean
    fun delete(table: String, id: Any): Boolean
    fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>>
    fun updateMany(table: String, values: Map<String, Any?>, predicates: List<Predicate>): Int
    fun deleteMany(table: String, predicates: List<Predicate>): Int
    fun <T> withTransaction(block: (Driver) -> T): T
}
```

Rows are plain `Map<String, Any?>` keyed by snake_case column name — the
driver layer speaks in these maps and the generated entity classes provide
the typed facade.

## Predicates

Sealed `Predicate` hierarchy —
`Leaf(field, op, value)`, `And`, `Or`, `HasEdge(edge)`, `HasEdgeWith(edge, inner)`.

**Ops:** `EQ`, `NEQ`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `NOT_IN`, `IS_NULL`,
`IS_NOT_NULL`, `CONTAINS`, `HAS_PREFIX`, `HAS_SUFFIX`.

## Transactions

`driver.withTransaction { txDriver -> ... }` runs a block
inside a transaction. The block receives a transaction-scoped driver; if it
completes normally the transaction commits, if it throws the transaction rolls
back. Nested `withTransaction` calls reuse the existing transaction.

