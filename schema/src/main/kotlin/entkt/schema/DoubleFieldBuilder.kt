package entkt.schema

class DoubleFieldBuilder internal constructor(name: String) : FieldBuilder<DoubleFieldBuilder, Double>(name, FieldType.DOUBLE) {
    fun min(min: Number): DoubleFieldBuilder = validate(Validators.min(min))
    fun max(max: Number): DoubleFieldBuilder = validate(Validators.max(max))
    fun positive(): DoubleFieldBuilder = validate(Validators.positive())
    fun negative(): DoubleFieldBuilder = validate(Validators.negative())
    fun nonNegative(): DoubleFieldBuilder = validate(Validators.nonNegative())
    fun default(value: Double): DoubleFieldBuilder = apply { setDefault(value) }
}
