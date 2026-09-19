package entkt.runtime.driver

/**
 * Requires each row's [EntitySchema.idColumn] to occur in the unique
 * [targetColumn] on [targetTable]. This is the reverse of a hasOne's
 * backing foreign key, checked at transaction commit so both rows can
 * be created or removed together. It adds no column.
 */
data class RequiredOneConstraint(
    val edgeName: String,
    val targetTable: String,
    val targetColumn: String,
) {
    /** Drivers normalize this name to their identifier length limit. */
    fun constraintName(table: String): String = "required_one_${table}_$edgeName"
}
