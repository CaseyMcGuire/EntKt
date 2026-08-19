package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * Fixture with a **nullable** belongsTo edge — used to exercise
 * create-hook adapter's create-hook adapter coverage for nullable FK
 * properties. The Mutation interface declares the FK as nullable
 * (`Long?`), so the adapter forwarder is also nullable; reads
 * return `null` until assigned, and writes accept both ids and
 * `null`.
 *
 * Counterpart of [Article], which has a *required* belongsTo
 * to the same `User` target — comparing the two pins both
 * non-null and nullable forwarder shapes.
 */
class Reminder : EntSchema("reminders", clientName = "reminders") {
    override fun id() = EntId.long()

    val body by string("body")
    val assignee by belongsTo<User>("assignee").nullable()
}
