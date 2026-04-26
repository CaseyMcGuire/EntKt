package entkt.schema

/**
 * A reference to a column that can be used in `index(...)` declarations.
 * This is the minimal interface needed for index linkage — it only
 * requires a column name.
 *
 * [FieldHandle] extends this with type information for FK validation.
 * [BelongsToBuilder.fk] returns this directly since the synthesized FK
 * column's type is not known until finalization.
 */
interface IndexableColumn {
    val fieldName: String
}

/**
 * A typed reference to a declared field on an [EntSchema]. Field handles
 * are returned by field builder methods (`string(...)`, `long(...)`, etc.)
 * and can be used as linkage inputs for `index(...)` and `.field(...)`.
 *
 * The type parameter [T] carries the field's value type so that
 * codegen-time validation can verify FK type compatibility.
 */
interface FieldHandle<out T> : IndexableColumn {
    val fieldType: FieldType
}
