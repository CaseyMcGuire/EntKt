package entkt.schema

class LongFieldBuilder(name: String) : FieldBuilder<LongFieldBuilder>(name, FieldType.LONG) {
    fun min(min: Number): LongFieldBuilder = validate(Validators.min(min))
    fun max(max: Number): LongFieldBuilder = validate(Validators.max(max))
    fun positive(): LongFieldBuilder = validate(Validators.positive())
    fun negative(): LongFieldBuilder = validate(Validators.negative())
    fun nonNegative(): LongFieldBuilder = validate(Validators.nonNegative())
}