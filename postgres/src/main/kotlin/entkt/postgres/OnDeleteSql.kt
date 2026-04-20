package entkt.postgres

import entkt.schema.OnDelete

/**
 * Map an [OnDelete] action (or null) to its SQL `ON DELETE` clause value.
 * When the action is null, the default is inferred from column nullability:
 * `SET NULL` for nullable FKs, `RESTRICT` for required ones.
 */
internal fun OnDelete?.toSql(columnNullable: Boolean = false): String = when (this) {
    OnDelete.CASCADE -> "CASCADE"
    OnDelete.SET_NULL -> {
        require(columnNullable) {
            "ON DELETE SET NULL is invalid on a NOT NULL column"
        }
        "SET NULL"
    }
    OnDelete.RESTRICT -> "RESTRICT"
    null -> if (columnNullable) "SET NULL" else "RESTRICT"
}
