package example.schema

import entkt.schema.EntMixin
import entkt.schema.fields

/**
 * A reusable mixin that stamps `created_at` and `updated_at` on every
 * schema that includes it. `created_at` is immutable — the update
 * builder won't get a setter for it.
 */
object TimestampMixin : EntMixin {
    override fun fields() = fields {
        time("created_at").immutable()
        time("updated_at")
    }
}
