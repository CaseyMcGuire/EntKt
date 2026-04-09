package entkt.schema

class IntFieldBuilder(name: String) : FieldBuilder<IntFieldBuilder>(name, FieldType.INT) {
    fun min(min: Number): IntFieldBuilder = validate(Validators.min(min))
    fun max(max: Number): IntFieldBuilder = validate(Validators.max(max))
    fun positive(): IntFieldBuilder = validate(Validators.positive())
    fun negative(): IntFieldBuilder = validate(Validators.negative())
    fun nonNegative(): IntFieldBuilder = validate(Validators.nonNegative())
}