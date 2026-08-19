package entkt.integrationtest.schema

import entkt.schema.DeletedAt
import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * Soft-deletable fixture for the soft-delete convention end-to-end
 * tests. Intentionally minimal — just an id, a body field, and the
 * `DeletedAt` mixin — so the test suite can pin convention behavior
 * (read-filter, soft-delete via update, restore via unfiltered
 * client, physical delete) without entangling with edge / FK /
 * hook setup.
 *
 * Schema-level partial-unique indexes are intentionally absent;
 * the convention pushes index strategy to the application, and
 * the soft-delete suite focuses on read-filter + write-mode
 * semantics rather than uniqueness behavior.
 */
class Memo : EntSchema("memos", clientName = "memos") {
    override fun id() = EntId.long()

    val softDelete = include(::DeletedAt)
    val body by text("body")
}
