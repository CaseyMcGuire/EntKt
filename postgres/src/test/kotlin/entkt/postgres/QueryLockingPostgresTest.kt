@file:OptIn(entkt.query.EntktInternal::class)

package entkt.postgres

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.query.TraversalSourceShape
import entkt.runtime.driver.ColumnMetadata
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.EdgeMetadata
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.IdStrategy
import entkt.runtime.driver.KotlinxJsonCodec
import entkt.runtime.query.QueryLockMode
import entkt.runtime.result.TransactionFailureState
import entkt.schema.FieldType
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QueryLockingPostgresTest {
    private val parents = EntitySchema(
        table = "query_lock_parents",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
        ),
        edges = emptyMap(),
    )
    private val items = EntitySchema(
        table = "query_lock_items",
        idColumn = "id",
        idStrategy = IdStrategy.AUTO_LONG,
        columns = listOf(
            ColumnMetadata("id", FieldType.LONG, nullable = false, primaryKey = true),
            ColumnMetadata("parent_id", FieldType.LONG, nullable = true),
            ColumnMetadata("name", FieldType.STRING, nullable = false),
        ),
        edges = mapOf("parent" to EdgeMetadata(parents.table, "parent_id", "id")),
    )
    private val ops = PostgresOperations(
        mapOf(items.table to items, parents.table to parents),
        PostgresValueCodec(KotlinxJsonCodec()),
    )

    @Test
    fun `locking appends a root-only clause after native ordering limit and offset`() {
        val predicates = listOf(Predicate.Leaf<Any>("name", Op.EQ, "selected"))
        val order = listOf(OrderField<Any>("id", OrderDirection.DESC))
        val ordinary = ops.buildSelectSql(items.table, predicates, order, 5, 2)
        val locking = ops.buildSelectSql(items.table, predicates, order, 5, 2, QueryLockMode.ForUpdate)

        assertEquals(
            "SELECT t0.* FROM \"query_lock_items\" AS t0 WHERE t0.\"name\" = ? " +
                "ORDER BY t0.\"id\" DESC LIMIT 5 OFFSET 2 FOR UPDATE OF t0",
            locking.sql,
        )
        assertEquals(ordinary.sql + " FOR UPDATE OF t0", locking.sql)
        assertEquals(ordinary.params, locking.params)
        assertEquals(listOf("selected"), locking.params.map { it.value })
    }

    @Test
    fun `traversal sources and edge predicate subqueries do not acquire locking clauses`() {
        val source = TraversalSourceShape<Any>(
            table = parents.table,
            selectedColumn = "id",
            predicates = listOf(Predicate.Leaf("name", Op.EQ, "source")),
            orderBy = listOf(OrderField("id", OrderDirection.ASC)),
            limit = 3,
            offset = 1,
            flags = emptySet(),
        )
        val prepared = ops.buildSelectSql(
            items.table,
            listOf(
                Predicate.HasEdgeFromShape<Any, Any>("parent", source),
                Predicate.HasEdgeWith<Any, Any>("parent", Predicate.Leaf("name", Op.EQ, "related")),
            ),
            emptyList(), 4, 2, QueryLockMode.ForUpdate,
        )

        assertTrue(prepared.sql.endsWith("LIMIT 4 OFFSET 2 FOR UPDATE OF t0"), prepared.sql)
        assertTrue("LIMIT 3 OFFSET 1" in prepared.sql, prepared.sql)
        assertEquals(1, Regex("FOR UPDATE").findAll(prepared.sql).count())
        assertEquals(listOf("source", "related"), prepared.params.map { it.value })
    }

    @Test
    fun `root driver rejects locking before borrowing any connection`() {
        val pool = Proxy.newProxyInstance(
            DataSource::class.java.classLoader, arrayOf(DataSource::class.java),
        ) { _, method, _ -> error("Unexpected pool access: ${method.name}") } as DataSource
        val driver = PostgresDriver(pool)
        assertTrue(driver.supportsQueryForUpdate)
        val failure = assertFailsWith<IllegalStateException> {
            driver.query(items.table, emptyList(), emptyList(), null, null, QueryLockMode.ForUpdate)
        }
        assertTrue("requires a transaction-scoped driver" in failure.message.orEmpty())
    }

    @Test
    fun `operation core rejects autocommit locking before preparing SQL`() {
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader, arrayOf(Connection::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getAutoCommit" -> true
                else -> error("Unexpected connection access: ${method.name}")
            }
        } as Connection
        val failure = assertFailsWith<IllegalStateException> {
            ops.query(connection, items.table, emptyList(), emptyList(), null, null, QueryLockMode.ForUpdate)
        }
        assertEquals("Query FOR UPDATE requires a transaction connection", failure.message)
    }

    @Test
    fun `transaction driver executes locking SQL on the pinned connection and rejects reuse after completion`() {
        val driver = freshDriver()
        var escaped: DatabaseDriver? = null
        val transaction = driver.withTransaction { tx ->
            escaped = tx
            assertTrue(tx.supportsQueryForUpdate)
            val parent = tx.insert(parents.table, mapOf("name" to "parent"))
            val item = tx.insert(items.table, mapOf("name" to "uncommitted", "parent_id" to parent.getValue("id")))
            val rows = tx.query(
                items.table,
                listOf(Predicate.Leaf<Any>("id", Op.EQ, item.getValue("id"))),
                emptyList(), 1, 0, QueryLockMode.ForUpdate,
            )
            // A query borrowing a separate connection cannot see this uncommitted row.
            assertEquals(listOf(item), rows)
            rows
        }
        assertIs<DriverTransactionResult.Success<*>>(transaction)
        assertFailsWith<IllegalStateException> {
            escaped!!.query(items.table, emptyList(), emptyList(), null, null, QueryLockMode.ForUpdate)
        }
    }

    @Test
    fun `handled locking statement failures still prevent reporting a committed transaction`() {
        freshDriver()
        val driver = PostgresDriver(SharedPostgres.dataSource).apply {
            registerAll(listOf(parents, items, items.copy(table = "query_lock_missing")))
        }
        val transaction = driver.withTransaction { tx ->
            tx.insert(items.table, mapOf("name" to "must roll back"))
            assertFailsWith<SQLException> {
                tx.query("query_lock_missing", emptyList(), emptyList(), null, null, QueryLockMode.ForUpdate)
            }
        }
        val failure = assertIs<DriverTransactionResult.Failed>(transaction)
        assertEquals(TransactionFailureState.NotCommitted, failure.transactionState)
        assertTrue(driver.query(items.table, emptyList(), emptyList(), null, null).isEmpty())
    }

    private fun freshDriver(): PostgresDriver {
        SharedPostgres.dataSource.connection.use { connection ->
            connection.createStatement().use {
                it.execute("DROP TABLE IF EXISTS query_lock_items, query_lock_parents, query_lock_missing")
            }
        }
        return PostgresDriver(SharedPostgres.dataSource, autoDdl = true).apply {
            registerAll(listOf(parents, items))
        }
    }
}
