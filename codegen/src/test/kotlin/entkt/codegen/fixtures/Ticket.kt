package entkt.codegen.fixtures

import entkt.schema.EntId
import entkt.schema.EntSchema

/** Shared enum-field schema fixture. */
class Ticket : EntSchema("tickets", clientName = "tickets") {
    override fun id() = EntId.int()
    val title by string("title")
    val priority by enum<Priority>("priority")
    val category by enum<Category>("category")
}
