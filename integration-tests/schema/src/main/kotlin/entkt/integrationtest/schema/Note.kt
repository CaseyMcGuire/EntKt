package entkt.integrationtest.schema

import entkt.schema.EntId
import entkt.schema.EntSchema

/**
 * Fixture for field-backed FK declaration names. The backing field's
 * Kotlin `val` name (`writer`) diverges from the storage column name
 * (`author_id`). Generated code must expose the FK as `writer` (entity
 * property, create / update setter, hook / privacy / validation
 * contexts), while the driver continues to read / write the
 * `author_id` column.
 *
 * Counterpart of [Article], which uses the implicit-FK form
 * (`belongsTo<User>("author")`) — comparing the two in the
 * integration tests pins the rename behavior end-to-end.
 */
class Note : EntSchema("notes", clientName = "notes") {
    override fun id() = EntId.long()

    val body by string("body")

    /** Backing column for the field-backed FK below. */
    val writer by long("author_id")

    val author by belongsTo<User>("author").field(writer)
}
