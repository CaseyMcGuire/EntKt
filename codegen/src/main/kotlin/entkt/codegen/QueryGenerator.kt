package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType

class QueryGenerator(
    private val packageName: String,
) {
    private val predicateClass = ClassName("entkt.query", "Predicate")
    private val opClass = ClassName("entkt.query", "Op")
    private val orderFieldClass = ClassName("entkt.query", "OrderField")
    private val orderDirectionClass = ClassName("entkt.query", "OrderDirection")

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Query"
        val queryClass = ClassName(packageName, className)

        val fields = schema.fields()
        val mixinFields = schema.mixins().flatMap { it.fields() }
        val allFields = fields + mixinFields
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val typeSpec = TypeSpec.classBuilder(className)
            .addProperty(
                PropertySpec.builder(
                    "predicates",
                    List::class.asClassName().parameterizedBy(predicateClass),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "orderFields",
                    List::class.asClassName().parameterizedBy(orderFieldClass),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("emptyList()")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("queryLimit", INT.copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("queryOffset", INT.copy(nullable = true))
                    .addModifiers(KModifier.PRIVATE)
                    .mutable(true)
                    .initializer("null")
                    .build()
            )
            .addFunction(buildWhere(queryClass))
            .addFunction(buildOrderAsc(queryClass))
            .addFunction(buildOrderDesc(queryClass))
            .addFunction(buildLimit(queryClass))
            .addFunction(buildOffset(queryClass))
            .addFunctions(allFields.flatMap { generatePredicateMethods(queryClass, it) })
            .addFunctions(edgeFks.flatMap { generateEdgePredicateMethods(queryClass, it) })
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    private fun buildWhere(queryClass: ClassName): FunSpec {
        return FunSpec.builder("where")
            .addParameter("predicate", predicateClass)
            .returns(queryClass)
            .addStatement("this.predicates = this.predicates + predicate")
            .addStatement("return this")
            .build()
    }

    private fun buildOrderAsc(queryClass: ClassName): FunSpec {
        return FunSpec.builder("orderAsc")
            .addParameter("field", String::class)
            .returns(queryClass)
            .addStatement(
                "this.orderFields = this.orderFields + %T(field, %T.ASC)",
                orderFieldClass,
                orderDirectionClass,
            )
            .addStatement("return this")
            .build()
    }

    private fun buildOrderDesc(queryClass: ClassName): FunSpec {
        return FunSpec.builder("orderDesc")
            .addParameter("field", String::class)
            .returns(queryClass)
            .addStatement(
                "this.orderFields = this.orderFields + %T(field, %T.DESC)",
                orderFieldClass,
                orderDirectionClass,
            )
            .addStatement("return this")
            .build()
    }

    private fun buildLimit(queryClass: ClassName): FunSpec {
        return FunSpec.builder("limit")
            .addParameter("n", INT)
            .returns(queryClass)
            .addStatement("this.queryLimit = n")
            .addStatement("return this")
            .build()
    }

    private fun buildOffset(queryClass: ClassName): FunSpec {
        return FunSpec.builder("offset")
            .addParameter("n", INT)
            .returns(queryClass)
            .addStatement("this.queryOffset = n")
            .addStatement("return this")
            .build()
    }

    private fun generatePredicateMethods(queryClass: ClassName, field: Field): List<FunSpec> {
        val propertyName = toCamelCase(field.name)
        val capName = propertyName.replaceFirstChar { it.uppercase() }
        val typeName = field.type.toTypeName()
        val methods = mutableListOf<FunSpec>()

        // All types get EQ and NEQ
        methods.add(predicateMethod("where${capName}Eq", field.name, "EQ", typeName, queryClass))
        methods.add(predicateMethod("where${capName}Neq", field.name, "NEQ", typeName, queryClass))

        // All types get IN and NOT_IN
        methods.add(predicateListMethod("where${capName}In", field.name, "IN", typeName, queryClass))
        methods.add(predicateListMethod("where${capName}NotIn", field.name, "NOT_IN", typeName, queryClass))

        // Nullable fields get IS_NULL and IS_NOT_NULL
        if (field.optional || field.nillable) {
            methods.add(predicateNullMethod("where${capName}IsNull", field.name, "IS_NULL", queryClass))
            methods.add(predicateNullMethod("where${capName}IsNotNull", field.name, "IS_NOT_NULL", queryClass))
        }

        // Comparable types get GT, GTE, LT, LTE
        if (field.type in listOf(FieldType.INT, FieldType.LONG, FieldType.FLOAT, FieldType.DOUBLE, FieldType.TIME)) {
            methods.add(predicateMethod("where${capName}Gt", field.name, "GT", typeName, queryClass))
            methods.add(predicateMethod("where${capName}Gte", field.name, "GTE", typeName, queryClass))
            methods.add(predicateMethod("where${capName}Lt", field.name, "LT", typeName, queryClass))
            methods.add(predicateMethod("where${capName}Lte", field.name, "LTE", typeName, queryClass))
        }

        // String types get CONTAINS, HAS_PREFIX, HAS_SUFFIX
        if (field.type in listOf(FieldType.STRING, FieldType.TEXT)) {
            methods.add(predicateMethod("where${capName}Contains", field.name, "CONTAINS", typeName, queryClass))
            methods.add(predicateMethod("where${capName}HasPrefix", field.name, "HAS_PREFIX", typeName, queryClass))
            methods.add(predicateMethod("where${capName}HasSuffix", field.name, "HAS_SUFFIX", typeName, queryClass))
        }

        return methods
    }

    private fun generateEdgePredicateMethods(queryClass: ClassName, fk: EdgeFk): List<FunSpec> {
        val capName = fk.propertyName.replaceFirstChar { it.uppercase() }
        val edgeCap = toCamelCase(fk.edgeName).replaceFirstChar { it.uppercase() }
        val typeName = fk.idType.toTypeName()
        val methods = mutableListOf<FunSpec>()

        methods.add(predicateMethod("where${capName}Eq", fk.columnName, "EQ", typeName, queryClass))
        methods.add(predicateMethod("where${capName}Neq", fk.columnName, "NEQ", typeName, queryClass))
        methods.add(predicateListMethod("where${capName}In", fk.columnName, "IN", typeName, queryClass))
        methods.add(predicateListMethod("where${capName}NotIn", fk.columnName, "NOT_IN", typeName, queryClass))

        // whereHasOwner / whereHasNoOwner — alias for IS_NOT_NULL / IS_NULL on the FK
        methods.add(predicateNullMethod("whereHas$edgeCap", fk.columnName, "IS_NOT_NULL", queryClass))
        methods.add(predicateNullMethod("whereHasNo$edgeCap", fk.columnName, "IS_NULL", queryClass))

        return methods
    }

    private fun predicateMethod(
        methodName: String,
        fieldName: String,
        op: String,
        typeName: com.squareup.kotlinpoet.TypeName,
        queryClass: ClassName,
    ): FunSpec {
        return FunSpec.builder(methodName)
            .addParameter("value", typeName)
            .returns(queryClass)
            .addStatement("return where(%T(%S, %T.%L, value))", predicateClass, fieldName, opClass, op)
            .build()
    }

    private fun predicateListMethod(
        methodName: String,
        fieldName: String,
        op: String,
        typeName: com.squareup.kotlinpoet.TypeName,
        queryClass: ClassName,
    ): FunSpec {
        return FunSpec.builder(methodName)
            .addParameter("values", List::class.asClassName().parameterizedBy(typeName))
            .returns(queryClass)
            .addStatement("return where(%T(%S, %T.%L, values))", predicateClass, fieldName, opClass, op)
            .build()
    }

    private fun predicateNullMethod(
        methodName: String,
        fieldName: String,
        op: String,
        queryClass: ClassName,
    ): FunSpec {
        return FunSpec.builder(methodName)
            .returns(queryClass)
            .addStatement("return where(%T(%S, %T.%L, null))", predicateClass, fieldName, opClass, op)
            .build()
    }
}