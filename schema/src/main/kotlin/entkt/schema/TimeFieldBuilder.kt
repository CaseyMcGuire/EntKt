package entkt.schema

class TimeFieldBuilder internal constructor(name: String) : FieldBuilder<TimeFieldBuilder, java.time.Instant>(name, FieldType.TIME) {
    fun defaultNow(): TimeFieldBuilder = apply { setDefault("now") }
    fun updateDefaultNow(): TimeFieldBuilder = apply { setUpdateDefault(UpdateDefault.Now) }
}
