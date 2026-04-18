package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import entkt.schema.Field
import entkt.schema.FieldType

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
}

/**
 * Returns the Kotlin type for a field. For typed enum fields (those with
 * [Field.enumClass] set), this returns the actual enum class name instead
 * of `String`.
 */
fun Field.resolvedTypeName(): TypeName {
    if (type == FieldType.ENUM && enumClass != null) {
        val qualifiedName = enumClass!!.qualifiedName
            ?: error("Enum class ${enumClass!!.simpleName} must have a qualified name")
        return ClassName.bestGuess(qualifiedName)
    }
    return type.toTypeName()
}