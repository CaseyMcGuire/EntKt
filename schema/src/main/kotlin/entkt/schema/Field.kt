package entkt.schema

data class Field(
    val name: String,
    val type: FieldType,
    val optional: Boolean = false,
    val nillable: Boolean = false,
    val unique: Boolean = false,
    val immutable: Boolean = false,
    val sensitive: Boolean = false,
    val default: Any? = null,
    val updateDefault: Any? = null,
    val enumValues: List<String>? = null,
    val enumClass: kotlin.reflect.KClass<out Enum<*>>? = null,
    val validators: List<Validator> = emptyList(),
    val comment: String? = null,
    val storageKey: String? = null,
)