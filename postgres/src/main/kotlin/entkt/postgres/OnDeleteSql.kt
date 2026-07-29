package entkt.postgres

import entkt.migrations.FkAction
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

/**
 * Map a resolved [FkAction] to its SQL `ON DELETE` clause value. The
 * migration model resolves DSL defaults at construction, so there is
 * no null case here — but the SET NULL sanity guard still applies.
 */
internal fun FkAction.toSql(columnNullable: Boolean = false): String = when (this) {
    FkAction.CASCADE -> "CASCADE"
    FkAction.SET_NULL -> {
        require(columnNullable) {
            "ON DELETE SET NULL is invalid on a NOT NULL column"
        }
        "SET NULL"
    }
    FkAction.RESTRICT -> "RESTRICT"
    FkAction.NO_ACTION -> "NO ACTION"
    FkAction.SET_DEFAULT -> "SET DEFAULT"
}
