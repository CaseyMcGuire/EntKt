# :runtime

`Driver` interface, `InMemoryDriver`, `EntitySchema`/`ColumnMetadata`/`EdgeMetadata`,
query `Predicate` hierarchy, `Op` enum.

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

## InMemoryDriver

Thread-safe in-process storage using `ConcurrentHashMap`
and `AtomicLong` id counters. Edge predicates recursively scan related tables
via registered `EdgeMetadata`. Supports transactions with snapshot isolation
and rollback on error. Used by the demo and as the parity oracle for the
Postgres driver tests.
