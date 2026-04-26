package example.schema

import entkt.schema.EntSchema

/**
 * A reusable base class that stamps `created_at` and `updated_at` on every
 * schema that extends it. `created_at` is immutable — the update
 * builder won't get a setter for it.
 */
abstract class TimestampedSchema(tableName: String) : EntSchema(tableName) {
    val createdAt = time("created_at").immutable()
    val updatedAt = time("updated_at")
}
