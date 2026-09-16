package entkt.codegen.fixtures

import entkt.schema.EntId
import entkt.schema.EntSchema

/** Shared schema fixture. Each test creates its own instance before finalizing it. */
class User : EntSchema("users", clientName = "users") {
    override fun id() = EntId.uuid()

    val createdAt by instant("created_at").immutable()
    val updatedAt by instant("updated_at")
    val name by string("name")
    val age by int("age").nullable()
    val email by string("email").unique()
    val active by bool("active").default(true)

    val cars by hasMany<Car>("cars")

    val idxCreatedAt = index("idx_created_at", createdAt)
    val idxNameEmail = index("idx_name_email", name, email).unique()
    val idxEmailActive = index("idx_email_active", email).where("active = true")
}
