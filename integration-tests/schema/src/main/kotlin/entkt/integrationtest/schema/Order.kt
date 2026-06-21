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
class Order : EntSchema("orders") {
    override fun id() = EntId.long()

    val quantity = int("quantity")
    val price = double("price")
    val discount = double("discount").nullable()
    val status = enum<OrderStatus>("status")
    val region = string("region").nullable()
    val placedAt = time("placed_at")
}
