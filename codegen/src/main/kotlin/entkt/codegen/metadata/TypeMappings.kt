package entkt.codegen.metadata

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.joinToCode
import entkt.schema.Field
import entkt.schema.FieldType
import kotlin.reflect.KClass
import kotlin.reflect.KType

fun FieldType.toTypeName(): TypeName = when (this) {
    FieldType.STRING -> String::class.asTypeName()
    FieldType.TEXT -> String::class.asTypeName()
    FieldType.BOOL -> Boolean::class.asTypeName()
    FieldType.INT -> Int::class.asTypeName()
    FieldType.LONG -> Long::class.asTypeName()
    FieldType.FLOAT -> Float::class.asTypeName()
    FieldType.DOUBLE -> Double::class.asTypeName()
    FieldType.TIME -> ClassName("java.time", "Instant")
    FieldType.UUID -> ClassName("java.util", "UUID")
    FieldType.BYTES -> ByteArray::class.asTypeName()
    FieldType.ENUM -> String::class.asTypeName()
    // The generated entity property + fromRow cast use the PgVector value type;
    // the driver owns decode (returns PgVector), so no per-field conversion.
    FieldType.PGVECTOR -> ClassName("entkt.postgres.vector", "PgVector")
    // JSON's property type is the supplied @Serializable type, resolved from
    // Field.jsonType in resolvedTypeName() (like ENUM) — never via this map.
    FieldType.JSON -> error("JSON field type is resolved from jsonType via resolvedTypeName(), not this map")
}

/**
 * Returns the Kotlin type for a field. For enum fields, this returns the
 * actual enum class name instead of `String`.
 */
fun Field.resolvedTypeName(): TypeName {
    if (type == FieldType.ENUM) {
        val klass = enumClass
            ?: error("Enum field '${name}' must have an enumClass — use enum<E>(\"$name\")")
        val qualifiedName = klass.qualifiedName
            ?: error("Enum class ${klass.simpleName} must have a qualified name")
        return ClassName.bestGuess(qualifiedName)
    }
    // JSON fields expose the supplied @Serializable type (like enum's actual
    // type), resolved from jsonType with type arguments intact — so
    // json<List<Rect>>("rects") emits List<Rect>, not a raw List.
    if (type == FieldType.JSON) {
        val jsonType = jsonType
            ?: error("JSON field '${name}' must have a jsonType — use json<X>(\"$name\")")
        return jsonType.asTypeName()
    }
    return type.toTypeName()
}

/** kotlinx.serialization.builtins: collection factories + primitive `serializer()` extensions. */
private const val SERIALIZATION_BUILTINS = "kotlinx.serialization.builtins"

/**
 * Classifiers whose serializer comes from a `kotlinx.serialization.builtins`
 * factory function (`ListSerializer(elementSerializer)`) rather than a
 * generated companion `serializer(...)`.
 */
private val BUILTIN_SERIALIZER_FACTORIES = mapOf(
    "kotlin.collections.List" to "ListSerializer",
    "kotlin.collections.Set" to "SetSerializer",
    "kotlin.collections.Map" to "MapSerializer",
    "kotlin.Pair" to "PairSerializer",
    "kotlin.Triple" to "TripleSerializer",
)

/**
 * Classifiers whose `serializer()` is a `kotlinx.serialization.builtins`
 * extension on the companion (`String.serializer()`) and therefore needs an
 * import — unlike the generated companion `serializer()` of a `@Serializable`
 * class, which resolves without one.
 */
private val BUILTIN_COMPANION_SERIALIZERS = setOf(
    "kotlin.String", "kotlin.Char", "kotlin.Boolean",
    "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
    "kotlin.Float", "kotlin.Double",
)

/**
 * Build the kotlinx serializer expression for a JSON field's [KType],
 * recursing into type arguments: `Meta.serializer()`,
 * `ListSerializer(Meta.serializer())`,
 * `MapSerializer(String.serializer(), Meta.serializer().nullable)`, or a
 * generic `@Serializable` user class's `Box.serializer(Int.serializer())`.
 *
 * Every leaf references a statically-resolved serializer, which keeps the
 * typed-JSON contract that a type without kotlinx serialization support fails
 * at consumer compile time (not as a late read failure) — and extends it to
 * each type argument. Enums used as arguments must themselves be
 * `@Serializable` so their companion `serializer()` is generated.
 */
internal fun jsonSerializerCodeBlock(fieldName: String, type: KType): CodeBlock {
    val klass = type.classifier as? KClass<*>
        ?: error("JSON field '$fieldName': type '$type' is not a concrete class")
    val qualifiedName = klass.qualifiedName
        ?: error("JSON field '$fieldName': class ${klass.simpleName} must have a qualified name")
    val argSerializers = type.arguments.map { arg ->
        val argType = arg.type
            ?: error("JSON field '$fieldName': type '$type' contains a star projection")
        jsonSerializerCodeBlock(fieldName, argType)
    }
    val factory = BUILTIN_SERIALIZER_FACTORIES[qualifiedName]
    val serializer = when {
        factory != null && argSerializers.isNotEmpty() ->
            CodeBlock.of("%M(%L)", MemberName(SERIALIZATION_BUILTINS, factory), argSerializers.joinToCode(", "))
        qualifiedName in BUILTIN_COMPANION_SERIALIZERS ->
            CodeBlock.of("%T.%M()", klass.asClassName(), MemberName(SERIALIZATION_BUILTINS, "serializer"))
        else ->
            // Generated companion serializer. Generic @Serializable classes
            // take their type arguments' serializers as parameters.
            CodeBlock.of("%T.serializer(%L)", klass.asClassName(), argSerializers.joinToCode(", "))
    }
    return if (type.isMarkedNullable) {
        CodeBlock.of("%L.%M", serializer, MemberName(SERIALIZATION_BUILTINS, "nullable"))
    } else {
        serializer
    }
}
