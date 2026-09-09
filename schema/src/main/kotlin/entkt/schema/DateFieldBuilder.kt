package entkt.schema

import java.time.LocalDate

/** A calendar date without a time of day or timezone. */
class DateFieldBuilder internal constructor(name: String) :
    FieldBuilder<DateFieldBuilder, LocalDate>(name, FieldType.DATE) {
    fun unique(): DateFieldBuilder = apply { setUnique() }
    fun default(value: LocalDate): DateFieldBuilder = apply { setDefault(value) }
}
