package example.schema

import entkt.schema.EntMixin

/**
 * A reusable local field bundle that stamps `created_at` and `updated_at`
 * on every schema that includes it. `created_at` is immutable — the update
 * builder won't get a setter for it.
 */
class Timestamps(scope: EntMixin.Scope) : EntMixin(scope) {
    val createdAt = time("created_at").immutable()
    val updatedAt = time("updated_at")
}
