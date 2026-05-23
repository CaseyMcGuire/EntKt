package entkt.schema

/**
 * Reusable mixin that declares a single nullable `deleted_at`
 * timestamp on the including schema.
 *
 * This is the schema half of the soft-delete convention
 * (see `docs/possible-features/schema/soft-delete.md`):
 *
 *   class Post : EntSchema("posts") {
 *       val softDelete = include(::DeletedAt)
 *       val title = string("title")
 *   }
 *
 * `deletedAt` is a normal nullable timestamp field — it appears on
 * the generated entity, on create/update builders, and in update
 * privacy / validation patches. That is intentional: soft deletion
 * is just an update to ordinary application state. The convention
 * is completed by the `entkt.runtime.ExcludeDeleted` interceptor,
 * which hides rows with non-null `deleted_at` from the entity's
 * default read paths when registered on the client.
 *
 * This mixin deliberately does **not** declare an index on
 * `deleted_at`. Applications add one when they need it — most
 * partial unique indexes scope on `deleted_at IS NULL` rather than
 * indexing the column itself, and a non-unique index is only
 * useful if soft-delete-state-based queries (e.g. "list deleted
 * rows for admin review") are hot enough to need one.
 */
class DeletedAt(scope: EntMixin.Scope) : EntMixin(scope) {
    val deletedAt = time("deleted_at").nullable()
}
