package entkt.schema

class InstantFieldBuilder internal constructor(name: String) : FieldBuilder<InstantFieldBuilder, java.time.Instant>(name, FieldType.INSTANT) {
    fun unique(): InstantFieldBuilder = apply { setUnique() }
    fun defaultNow(): InstantFieldBuilder = apply { setDefault("now") }
    fun updateDefaultNow(): InstantFieldBuilder = apply { setUpdateDefault(UpdateDefault.Now) }
}
