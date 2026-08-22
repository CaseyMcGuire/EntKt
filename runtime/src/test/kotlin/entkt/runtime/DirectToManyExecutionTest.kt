@file:OptIn(entkt.query.EntktInternal::class)

package entkt.runtime

import entkt.query.Op
import entkt.query.OrderDirection
import entkt.query.OrderField
import entkt.query.Predicate
import entkt.runtime.driver.DirectToManyQuery
import entkt.runtime.driver.DirectToManyWindowCapability
import entkt.runtime.driver.DatabaseDriver
import entkt.runtime.driver.DriverTransactionResult
import entkt.runtime.driver.EntitySchema
import entkt.runtime.driver.PerParentWindow
import entkt.runtime.driver.RelatedRow
import entkt.runtime.driver.RelatedRows
import entkt.runtime.driver.executeDirectToMany
import entkt.runtime.query.EagerWindowStrategy
import entkt.runtime.query.FrozenQuerySpec
import entkt.runtime.query.QuerySpecBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit contract for the phase-2A runtime pieces: the
 * [executeDirectToMany] plan/fallback selection with its phase-1 data
 * gates, the [FrozenQuerySpec] structural-attribution slices, and the
 * [QuerySpecBuilder] single-bind-transport budget accounting.
 */
class DirectToManyExecutionTest {

    private val order = listOf(OrderField<Any>("id", OrderDirection.ASC))

    private fun query(
        sourceKeys: List<Any> = listOf(1L, 2L),
        window: PerParentWindow = PerParentWindow(offset = 0, limit = null),
        targetPredicates: List<Predicate<*>> = emptyList(),
    ) = DirectToManyQuery(
        targetTable = "posts",
        sourceKeys = sourceKeys,
        targetForeignKey = "author_id",
        targetPredicates = targetPredicates,
        effectiveOrder = order,
        window = window,
    )

    /**
     * Fake capturing which driver read executed. [queryRows] feed the
     * emulated path; [nativeResult] feeds the native one.
     */
    private class CapturingDriver(
        private val capability: DirectToManyWindowCapability,
        private val queryRows: List<Map<String, Any?>> = emptyList(),
        private val nativeResult: RelatedRows? = null,
    ) : DatabaseDriver {
        var queryCalls = 0
        var nativeCalls = 0
        var lastQueryPredicates: List<Predicate<*>>? = null
        var lastQueryOrderBy: List<OrderField<*>>? = null
        var lastQueryBounds: Pair<Int?, Int?>? = null
        var lastNativeQuery: DirectToManyQuery? = null
        var capabilityProbes = 0

        override fun directToManyWindowCapability(): DirectToManyWindowCapability {
            capabilityProbes++
            return capability
        }

        override fun queryDirectToMany(query: DirectToManyQuery): RelatedRows {
            nativeCalls++
            lastNativeQuery = query
            return nativeResult ?: RelatedRows(emptyList(), EagerWindowStrategy.STORAGE_NATIVE)
        }

        override fun query(
            table: String,
            predicates: List<Predicate<*>>,
            orderBy: List<OrderField<*>>,
            limit: Int?,
            offset: Int?,
        ): List<Map<String, Any?>> {
            queryCalls++
            lastQueryPredicates = predicates
            lastQueryOrderBy = orderBy
            lastQueryBounds = limit to offset
            return queryRows
        }

        override fun register(schema: EntitySchema) {}
        override fun registerAll(schemas: List<EntitySchema>) {}
        override fun requireBindCapacity(minimumParameters: Long, table: String) {}
        override fun registeredIdColumn(table: String): String = "id"
        override fun insert(table: String, values: Map<String, Any?>): Map<String, Any?> = error("unused")
        override fun update(table: String, id: Any, values: Map<String, Any?>): Map<String, Any?>? = error("unused")
        override fun byId(table: String, id: Any): Map<String, Any?>? = error("unused")
        override fun count(table: String, predicates: List<Predicate<*>>): Long = error("unused")
        override fun exists(table: String, predicates: List<Predicate<*>>): Boolean = error("unused")
        override fun delete(table: String, id: Any): Boolean = error("unused")
        override fun insertMany(table: String, values: List<Map<String, Any?>>): List<Map<String, Any?>> =
            error("unused")
        override fun updateMany(
            table: String,
            values: Map<String, Any?>,
            predicates: List<Predicate<*>>,
        ): Int = error("unused")
        override fun deleteMany(table: String, predicates: List<Predicate<*>>): Int = error("unused")
        override fun <T> withTransaction(block: (DatabaseDriver) -> T): DriverTransactionResult<T> = error("unused")
    }

    // ---------- executeDirectToMany ----------

    @Test
    fun `native capability routes through queryDirectToMany and returns its result`() {
        val nativeRows = RelatedRows(
            listOf(RelatedRow(1L, mapOf("id" to 10L, "author_id" to 1L))),
            EagerWindowStrategy.STORAGE_NATIVE,
        )
        val driver = CapturingDriver(DirectToManyWindowCapability.NATIVE, nativeResult = nativeRows)
        val q = query()

        val result = executeDirectToMany(
            driver,
            q,
            emulationPredicates = emptyList(),
            capability = DirectToManyWindowCapability.NATIVE,
        )

        assertSame(nativeRows, result)
        assertEquals(1, driver.nativeCalls)
        assertEquals(0, driver.queryCalls)
        assertSame(q, driver.lastNativeQuery)
        // Routing uses the caller's ONE capability sample — the
        // executor must not probe the driver again, or budgeting and
        // routing could disagree against an unstable capability.
        assertEquals(0, driver.capabilityProbes)
    }

    @Test
    fun `emulated capability issues the phase-1 query with the complete predicate list`() {
        val structural = Predicate.Leaf<Any>("author_id", Op.IN, listOf(1L, 2L))
        val caller = Predicate.Leaf<Any>("published", Op.EQ, true)
        val emulationPredicates = listOf(caller, structural)
        val rows = listOf(
            mapOf("id" to 10L, "author_id" to 1L),
            mapOf("id" to 11L, "author_id" to 2L),
        )
        val driver = CapturingDriver(DirectToManyWindowCapability.EMULATED, queryRows = rows)

        val result = executeDirectToMany(
            driver,
            query(),
            emulationPredicates,
            capability = DirectToManyWindowCapability.EMULATED,
        )

        assertEquals(0, driver.nativeCalls)
        assertEquals(1, driver.queryCalls)
        // Byte-identical phase-1 driver interaction: the complete
        // ordered frozen predicate list, the effective order, and no
        // driver-level bounds.
        assertSame(emulationPredicates, driver.lastQueryPredicates)
        assertSame(order, driver.lastQueryOrderBy)
        assertEquals(null to null, driver.lastQueryBounds)
        // Unwindowed rows, each wrapped with the FK value as its
        // source key, in driver result order.
        assertEquals(EagerWindowStrategy.IN_MEMORY_EMULATED, result.strategy)
        assertEquals(listOf<Any?>(1L, 2L), result.rows.map { it.sourceKey })
        assertEquals(rows, result.rows.map { it.targetRow })
    }

    @Test
    fun `an empty parent set performs no driver read on either capability`() {
        for (capability in DirectToManyWindowCapability.entries) {
            val driver = CapturingDriver(capability)
            val result = executeDirectToMany(
                driver,
                query(sourceKeys = emptyList()),
                emulationPredicates = emptyList(),
                capability = capability,
            )
            assertEquals(0, driver.queryCalls, "capability=$capability")
            assertEquals(0, driver.nativeCalls, "capability=$capability")
            assertTrue(result.rows.isEmpty(), "capability=$capability")
            val expected = when (capability) {
                DirectToManyWindowCapability.NATIVE -> EagerWindowStrategy.STORAGE_NATIVE
                DirectToManyWindowCapability.EMULATED -> EagerWindowStrategy.IN_MEMORY_EMULATED
            }
            assertEquals(expected, result.strategy, "capability=$capability")
        }
    }

    @Test
    fun `a limit-zero window performs no driver read on either capability`() {
        for (capability in DirectToManyWindowCapability.entries) {
            val driver = CapturingDriver(capability)
            val result = executeDirectToMany(
                driver,
                query(window = PerParentWindow(offset = 0, limit = 0)),
                emulationPredicates = emptyList(),
                capability = capability,
            )
            assertEquals(0, driver.queryCalls, "capability=$capability")
            assertEquals(0, driver.nativeCalls, "capability=$capability")
            assertTrue(result.rows.isEmpty(), "capability=$capability")
        }
    }

    @Test
    fun `per-parent windows reject negative bounds`() {
        assertFailsWith<IllegalArgumentException> { PerParentWindow(offset = -1, limit = null) }
        assertFailsWith<IllegalArgumentException> { PerParentWindow(offset = 0, limit = -1) }
    }

    // ---------- FrozenQuerySpec attribution slices ----------

    private fun leaf(field: String): Predicate<Any> = Predicate.Leaf(field, Op.EQ, 1)

    @Test
    fun `frozen spec slices predicates by positional attribution`() {
        val caller = leaf("a")
        val structural = leaf("b")
        val interceptor = leaf("c")
        val spec = FrozenQuerySpec(
            table = "t",
            predicates = listOf(caller, structural, interceptor),
            orderBy = emptyList(),
            limit = null,
            offset = null,
            flags = emptySet(),
            annotations = emptyMap(),
            callerPredicateCount = 1,
            structuralPredicateCount = 1,
        )
        assertEquals(listOf<Predicate<Any>>(structural), spec.structuralPredicates)
        assertEquals(listOf(caller, interceptor), spec.nonStructuralPredicates)
    }

    @Test
    fun `frozen spec attribution defaults treat every predicate as caller-authored`() {
        val spec = FrozenQuerySpec(
            table = "t",
            predicates = listOf(leaf("a"), leaf("b")),
            orderBy = emptyList(),
            limit = null,
            offset = null,
            flags = emptySet(),
            annotations = emptyMap(),
        )
        assertTrue(spec.structuralPredicates.isEmpty())
        assertEquals(spec.predicates, spec.nonStructuralPredicates)
    }

    @Test
    fun `frozen spec rejects attribution counts that overflow the predicate list`() {
        assertFailsWith<IllegalArgumentException> {
            FrozenQuerySpec(
                table = "t",
                predicates = listOf(leaf("a")),
                orderBy = emptyList(),
                limit = null,
                offset = null,
                flags = emptySet(),
                annotations = emptyMap(),
                callerPredicateCount = 1,
                structuralPredicateCount = 1,
            )
        }
    }

    @Test
    fun `the builder freezes caller structural and interceptor attribution positionally`() {
        val builder = QuerySpecBuilder<Any>(
            table = "t",
            entity = Any::class,
            callerPredicates = listOf(leaf("caller")),
            structuralPredicates = listOf(leaf("structural")),
            orderBy = emptyList(),
            callerLimit = null,
            offset = null,
            flags = emptySet(),
        )
        builder.addInterceptorPredicate(leaf("interceptor"))
        val frozen = builder.freeze()

        assertEquals(1, frozen.callerPredicateCount)
        assertEquals(1, frozen.structuralPredicateCount)
        assertEquals(
            listOf("caller", "structural", "interceptor"),
            frozen.predicates.map { (it as Predicate.Leaf<*>).field },
        )
        assertEquals(
            listOf("structural"),
            frozen.structuralPredicates.map { (it as Predicate.Leaf<*>).field },
        )
        assertEquals(
            listOf("caller", "interceptor"),
            frozen.nonStructuralPredicates.map { (it as Predicate.Leaf<*>).field },
        )
    }

    // ---------- structural single-bind-transport budget ----------

    @Test
    fun `single-bind transport charges one parameter per structural predicate`() {
        val parents = List(10_000) { it.toLong() }
        val structural = Predicate.Leaf<Any>("author_id", Op.IN, parents)
        var observedMinimum: Long? = null

        QuerySpecBuilder<Any>(
            table = "t",
            entity = Any::class,
            callerPredicates = listOf(Predicate.Leaf("x", Op.IN, listOf(1, 2, 3))),
            structuralPredicates = listOf(structural),
            orderBy = emptyList(),
            callerLimit = null,
            offset = null,
            flags = emptySet(),
            requireBindCapacity = { observedMinimum = it },
            structuralSingleBindTransport = true,
        )

        // Caller IN charges its 3 scalar binds; the structural IN
        // charges 1 (the typed-array transport), not 10,000.
        assertEquals(4L, observedMinimum)
    }

    @Test
    fun `without the transport the structural predicate charges its scalar operand size`() {
        val parents = List(10_000) { it.toLong() }
        var observedMinimum: Long? = null

        QuerySpecBuilder<Any>(
            table = "t",
            entity = Any::class,
            callerPredicates = emptyList(),
            structuralPredicates = listOf(Predicate.Leaf("author_id", Op.IN, parents)),
            orderBy = emptyList(),
            callerLimit = null,
            offset = null,
            flags = emptySet(),
            requireBindCapacity = { observedMinimum = it },
        )

        assertEquals(10_000L, observedMinimum)
    }

}
