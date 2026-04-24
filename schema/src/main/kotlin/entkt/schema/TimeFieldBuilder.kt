package entkt.schema

class TimeFieldBuilder(name: String) : FieldBuilder<TimeFieldBuilder>(name, FieldType.TIME) {
    fun defaultNow(): TimeFieldBuilder = apply { setDefault("now") }
    fun updateDefaultNow(): TimeFieldBuilder = apply { setUpdateDefault(UpdateDefault.Now) }
}