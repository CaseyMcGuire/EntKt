package entkt.schema

class FloatFieldBuilder(name: String) : FieldBuilder<FloatFieldBuilder>(name, FieldType.FLOAT) {
    fun min(min: Number): FloatFieldBuilder = validate(Validators.min(min))
    fun max(max: Number): FloatFieldBuilder = validate(Validators.max(max))
    fun positive(): FloatFieldBuilder = validate(Validators.positive())
    fun negative(): FloatFieldBuilder = validate(Validators.negative())
    fun nonNegative(): FloatFieldBuilder = validate(Validators.nonNegative())
}