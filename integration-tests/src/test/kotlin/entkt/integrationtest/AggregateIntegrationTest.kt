package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Order
import entkt.integrationtest.schema.OrderStatus
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.AggregateBucket
import entkt.runtime.EntResult
import entkt.runtime.PrivacyContext
import entkt.runtime.Viewer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the generated raw aggregate terminals over a real
 * Postgres + the full generated client. The headline is that an enum group key
 * comes back as the decoded enum (not its stored String) — proving the
 * EnumColumn decode metadata threads all the way through codegen → driver.
 */
class AggregateIntegrationTest : PostgresTestBase() {

    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private fun client(driver: PostgresDriver = resetAndDriver()): EntClient =
        EntClient(driver) { privacyContext { PrivacyContext(Viewer.System) } }

    private fun seed(c: EntClient) {
        c.orders.create { quantity = 2; price = 10.0; status = OrderStatus.PENDING; region = "us"; placedAt = t0 }.saveOrThrow()
        c.orders.create { quantity = 3; price = 20.0; status = OrderStatus.PENDING; region = "us"; placedAt = t0.plusSeconds(60) }.saveOrThrow()
        c.orders.create { quantity = 5; price = 30.0; status = OrderStatus.SHIPPED; region = null; placedAt = t0.plusSeconds(120) }.saveOrThrow()
        c.orders.create { quantity = 1; price = 5.0; status = OrderStatus.SHIPPED; region = "eu"; placedAt = t0.plusSeconds(180) }.saveOrThrow()
    }

    @Test
    fun `ungrouped scalar terminals are typed and correct`() {
        val c = client(); seed(c)
        assertEquals(4L, c.orders.query().rawCount())

        val sumQty: Long? = c.orders.query().rawSum(Order.quantity)   // integral → Long?
        assertEquals(11L, sumQty)
        val sumPrice: Double? = c.orders.query().rawSum(Order.price)  // floating → Double?
        assertEquals(65.0, sumPrice)
        val avgPrice: Double? = c.orders.query().rawAvg(Order.price)
        assertEquals(16.25, avgPrice)
        val maxQty: Int? = c.orders.query().rawMax(Order.quantity)    // min/max keep the column's type
        assertEquals(5, maxQty)
        assertEquals(t0, c.orders.query().rawMin(Order.placedAt))
        assertEquals(t0.plusSeconds(180), c.orders.query().rawMax(Order.placedAt))
    }

    @Test
    fun `grouping by an enum decodes keys back to the enum`() {
        val c = client(); seed(c)
        val byStatus: List<AggregateBucket<OrderStatus, Long>> = c.orders.query().rawCountBy(Order.status)
        assertEquals(
            mapOf(OrderStatus.PENDING to 2L, OrderStatus.SHIPPED to 2L),
            byStatus.associate { it.key to it.value },
        )
    }

    @Test
    fun `grouped sum and avg by an enum key`() {
        val c = client(); seed(c)
        assertEquals(
            mapOf<OrderStatus, Long?>(OrderStatus.PENDING to 5L, OrderStatus.SHIPPED to 6L),
            c.orders.query().rawSumBy(Order.status, Order.quantity).associate { it.key to it.value },
        )
        assertEquals(
            mapOf<OrderStatus, Double?>(OrderStatus.PENDING to 15.0, OrderStatus.SHIPPED to 17.5),
            c.orders.query().rawAvgBy(Order.status, Order.price).associate { it.key to it.value },
        )
    }

    @Test
    fun `grouping by a nullable column folds NULLs into a null-key bucket`() {
        val c = client(); seed(c)
        val byRegion: List<AggregateBucket<String?, Long>> = c.orders.query().rawCountBy(Order.region)
        assertEquals(
            mapOf("us" to 2L, "eu" to 1L, null to 1L),
            byRegion.associate { it.key to it.value },
        )
    }

    @Test
    fun `caller predicates narrow the aggregate`() {
        val c = client(); seed(c)
        val shippedQty = c.orders.query { where(Order.status eq OrderStatus.SHIPPED) }.rawSum(Order.quantity)
        assertEquals(6L, shippedQty)
    }

    @Test
    fun `OrError returns Ok on the happy path`() {
        val c = client(); seed(c)
        val r = c.orders.query().rawSumOrError(Order.quantity)
        assertTrue(r is EntResult.Ok)
        assertEquals(11L, (r as EntResult.Ok).value)
    }

    @Test
    fun `empty set has count zero, null metrics, and no buckets`() {
        val c = client()  // no seed
        assertEquals(0L, c.orders.query().rawCount())
        assertNull(c.orders.query().rawSum(Order.quantity))
        assertNull(c.orders.query().rawAvg(Order.price))
        assertNull(c.orders.query().rawMax(Order.placedAt))
        assertTrue(c.orders.query().rawCountBy(Order.status).isEmpty())
    }
}
