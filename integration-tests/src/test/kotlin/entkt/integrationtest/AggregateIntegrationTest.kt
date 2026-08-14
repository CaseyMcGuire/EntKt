package entkt.integrationtest

import entkt.integrationtest.ent.EntClient
import entkt.integrationtest.ent.Order
import entkt.integrationtest.schema.OrderStatus
import entkt.integrationtest.support.PostgresTestBase
import entkt.postgres.PostgresDriver
import entkt.runtime.query.AggregateBucket
import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.query.QueryInterceptor
import entkt.runtime.privacy.Viewer
import entkt.runtime.result.EntQueryRejectedException
import entkt.runtime.result.ReadResult
import entkt.runtime.result.getOrThrow
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end coverage of the generated raw aggregate terminals over a real
 * Postgres + the full generated client. Every raw aggregate terminal returns
 * `ReadResult<...>` directly — `Success` carries the typed scalar/bucket
 * payload (SQL-null aggregates surface as `Success(null)`), and interceptor
 * rejection is `Failed(EntQueryRejectedException)`. The headline is that an
 * enum group key comes back as the decoded enum (not its stored String) —
 * proving the EnumColumn decode metadata threads all the way through
 * codegen → driver.
 */
class AggregateIntegrationTest : PostgresTestBase() {

    private val t0 = Instant.parse("2024-01-01T00:00:00Z")

    private fun client(driver: PostgresDriver = resetAndDriver()): EntClient =
        EntClient(driver) { privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) } }

    private fun seed(c: EntClient) {
        c.orders.create { quantity = 2; price = 10.0; status = OrderStatus.PENDING; region = "us"; placedAt = t0 }.save().getOrThrow()
        c.orders.create { quantity = 3; price = 20.0; status = OrderStatus.PENDING; region = "us"; placedAt = t0.plusSeconds(60) }.save().getOrThrow()
        c.orders.create { quantity = 5; price = 30.0; status = OrderStatus.SHIPPED; region = null; placedAt = t0.plusSeconds(120) }.save().getOrThrow()
        c.orders.create { quantity = 1; price = 5.0; status = OrderStatus.SHIPPED; region = "eu"; placedAt = t0.plusSeconds(180) }.save().getOrThrow()
    }

    @Test
    fun `ungrouped scalar terminals are typed and correct`() {
        val c = client(); seed(c)
        assertEquals(4L, c.orders.query().rawCount().getOrThrow())

        val sumQty: Long? = c.orders.query().rawSum(Order.quantity).getOrThrow()   // integral → Long?
        assertEquals(11L, sumQty)
        val sumPrice: Double? = c.orders.query().rawSum(Order.price).getOrThrow()  // floating → Double?
        assertEquals(65.0, sumPrice)
        val avgPrice: Double? = c.orders.query().rawAvg(Order.price).getOrThrow()
        assertEquals(16.25, avgPrice)
        val maxQty: Int? = c.orders.query().rawMax(Order.quantity).getOrThrow()    // min/max keep the column's type
        assertEquals(5, maxQty)
        assertEquals(t0, c.orders.query().rawMin(Order.placedAt).getOrThrow())
        assertEquals(t0.plusSeconds(180), c.orders.query().rawMax(Order.placedAt).getOrThrow())
    }

    @Test
    fun `grouping by an enum decodes keys back to the enum`() {
        val c = client(); seed(c)
        val byStatus: List<AggregateBucket<OrderStatus, Long>> =
            c.orders.query().rawCountBy(Order.status).getOrThrow()
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
            c.orders.query().rawSumBy(Order.status, Order.quantity).getOrThrow().associate { it.key to it.value },
        )
        assertEquals(
            mapOf<OrderStatus, Double?>(OrderStatus.PENDING to 15.0, OrderStatus.SHIPPED to 17.5),
            c.orders.query().rawAvgBy(Order.status, Order.price).getOrThrow().associate { it.key to it.value },
        )
    }

    @Test
    fun `grouping by a nullable column folds NULLs into a null-key bucket`() {
        val c = client(); seed(c)
        val byRegion: List<AggregateBucket<String?, Long>> =
            c.orders.query().rawCountBy(Order.region).getOrThrow()
        assertEquals(
            mapOf("us" to 2L, "eu" to 1L, null to 1L),
            byRegion.associate { it.key to it.value },
        )
    }

    @Test
    fun `caller predicates narrow the aggregate`() {
        val c = client(); seed(c)
        val shippedQty = c.orders.query { where(Order.status eq OrderStatus.SHIPPED) }
            .rawSum(Order.quantity)
            .getOrThrow()
        assertEquals(6L, shippedQty)
    }

    @Test
    fun `rawSum returns Success on the happy path`() {
        val c = client(); seed(c)
        val r = c.orders.query().rawSum(Order.quantity)
        val ok = assertIs<ReadResult.Success<Long?>>(r)
        assertEquals(11L, ok.value)
    }

    @Test
    fun `rawSum surfaces interceptor rejection as Failed(EntQueryRejectedException)`() {
        val driver = resetAndDriver()
        val c = EntClient(driver) {
            privacyContext { PrivacyContext(Viewer.PrivacyBypass("test")) }
            interceptors {
                orders(
                    QueryInterceptor { scope, _ -> scope.reject("nope", code = "agg_rej") },
                    name = "agg-rejector",
                )
            }
        }
        val r = c.orders.query().rawSum(Order.quantity)
        val failed = assertIs<ReadResult.Failed>(r)
        val rejected = assertIs<EntQueryRejectedException>(failed.exception)
        assertEquals("agg_rej", rejected.code)
        assertEquals("agg-rejector", rejected.interceptor)
    }

    @Test
    fun `empty set has count zero, null metrics, and no buckets`() {
        val c = client()  // no seed
        assertEquals(0L, c.orders.query().rawCount().getOrThrow())
        assertNull(c.orders.query().rawSum(Order.quantity).getOrThrow())
        assertNull(c.orders.query().rawAvg(Order.price).getOrThrow())
        assertNull(c.orders.query().rawMax(Order.placedAt).getOrThrow())
        assertTrue(c.orders.query().rawCountBy(Order.status).getOrThrow().isEmpty())
    }
}
