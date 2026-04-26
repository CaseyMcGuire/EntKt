package entkt.schema

class IndexBuilder internal constructor(
    private val name: String,
    private val columns: List<IndexableColumn>,
) {
    internal var frozen: Boolean = false

    private fun checkNotFrozen() {
        check(!frozen) { "Index cannot be modified after schema finalization" }
    }

    private var unique: Boolean = false
    private var where: String? = null

    fun unique(): IndexBuilder = apply { checkNotFrozen(); unique = true }
    fun where(clause: String): IndexBuilder = apply { checkNotFrozen(); where = clause }

    fun build(): Index {
        require(columns.isNotEmpty()) { "Index must have at least one field" }
        return Index(
            name = name,
            fields = columns.map { it.fieldName },
            unique = unique,
            where = where,
        )
    }
}
