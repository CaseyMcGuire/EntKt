package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/** Status of an [Order] — an enum group key for aggregate coverage. */
enum class OrderStatus { PENDING, SHIPPED, DELIVERED }

/**
 * Aggregate-coverage fixture: an integral metric (quantity), floating metrics
 * (price + nullable discount), an enum group key (status), a nullable string
 * group key (region), and a time column for min/max.
 */
class Order : EntSchema("orders", clientName = "orders") {
    override fun id() = EntId.long()

    val quantity by int("quantity")
    val price by double("price")
    val discount by double("discount").nullable()
    val status by enum<OrderStatus>("status")
    val region by string("region").nullable()
    val placedAt by time("placed_at")
}
