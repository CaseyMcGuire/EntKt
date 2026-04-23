package entkt.schema

class TimeFieldBuilder(name: String) : FieldBuilder<TimeFieldBuilder>(name, FieldType.TIME) {
    fun updateDefaultNow(): TimeFieldBuilder = apply { setUpdateDefault(UpdateDefault.Now) }
}