package entkt.codegen.fixtures

import entkt.schema.EntId
import entkt.schema.EntSchema

/** Shared schema fixture. Each test creates its own instance before finalizing it. */
class Car : EntSchema("cars", clientName = "cars") {
    override fun id() = EntId.int()
    val model by string("model")
    val year by int("year")
    val price by float("price").nullable()

    val user by belongsTo<User>("user_id").inverse(User::cars)
}
