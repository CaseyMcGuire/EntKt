package entkt.schema

class IntFieldBuilder internal constructor(name: String) : FieldBuilder<IntFieldBuilder, Int>(name, FieldType.INT) {
    fun min(min: Number): IntFieldBuilder = validate(Validators.min(min))
    fun max(max: Number): IntFieldBuilder = validate(Validators.max(max))
    fun positive(): IntFieldBuilder = validate(Validators.positive())
    fun negative(): IntFieldBuilder = validate(Validators.negative())
    fun nonNegative(): IntFieldBuilder = validate(Validators.nonNegative())
    fun default(value: Int): IntFieldBuilder = apply { setDefault(value) }
}
