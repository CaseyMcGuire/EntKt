package entkt.schema

class EnumFieldBuilder internal constructor(name: String) : FieldBuilder<EnumFieldBuilder, Enum<*>>(name, FieldType.ENUM) {
    /**
     * Set the column default to [value]. The default is rendered into
     * migration DDL as the constant's [Enum.name] (e.g. `DEFAULT 'MEDIUM'`).
     *
     * Note: because the default tracks the constant *name*, renaming the
     * constant later changes the rendered default and produces a
     * metadata-only `SET DEFAULT` migration that does not backfill existing
     * rows. See the rename caveat on `EntSchema.enum`.
     */
    fun default(value: Enum<*>): EnumFieldBuilder = apply { setDefault(value) }
}
