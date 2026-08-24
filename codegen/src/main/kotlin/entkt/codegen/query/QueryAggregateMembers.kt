package entkt.codegen.query

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import entkt.codegen.kotlinpoet.annotation
import entkt.codegen.kotlinpoet.codeBlock
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.parameter
import entkt.codegen.kotlinpoet.statement

private val READ_RESULT = ClassName("entkt.runtime.result", "ReadResult")
private val AGG_FUNCTION = ClassName("entkt.runtime.query", "AggregateFunction")
private val AGG_BUCKET = ClassName("entkt.runtime.query", "AggregateBucket")
private val COMPARABLE_COLUMN = ClassName("entkt.query", "ComparableColumn")
private val NUMERIC_COLUMN = ClassName("entkt.query", "NumericColumn")
private val INTEGRAL_COLUMN = ClassName("entkt.query", "IntegralColumn")
private val FLOATING_COLUMN = ClassName("entkt.query", "FloatingColumn")
private val GROUPABLE_COLUMN = ClassName("entkt.query", "GroupableColumn")
private val NULLABLE_GROUPABLE_COLUMN = ClassName("entkt.query", "NullableGroupableColumn")
private val KOTLIN_COMPARABLE = ClassName("kotlin", "Comparable")

/** Generate the raw count terminal as a thin runtime delegation. */
internal fun buildRawCount(): FunSpec = function(
    "rawCount",
    returnType = READ_RESULT.parameterizedBy(LONG),
) {
    addKdoc("Count matching rows without materializing entities or evaluating LOAD privacy.\n")
    statement("return _readQueryEvaluator.rawCount { captureEntityQuery() }")
}

/** Generate the raw existence terminal as a thin runtime delegation. */
internal fun buildRawExists(): FunSpec = function(
    "rawExists",
    returnType = READ_RESULT.parameterizedBy(BOOLEAN),
) {
    addKdoc("Test whether the configured storage window contains a row without evaluating LOAD privacy.\n")
    statement("return _readQueryEvaluator.rawExists { captureEntityQuery() }")
}

/** Generate typed raw aggregate terminals over the shared runtime aggregate execution. */
internal fun buildAggregateTerminals(entityClass: ClassName): List<FunSpec> {
    val specs = mutableListOf<FunSpec>()
    val suppress = annotation(ClassName("kotlin", "Suppress")) {
        addMember("%S", "UNCHECKED_CAST")
    }

    fun comparableT(): TypeVariableName {
        val type = TypeVariableName("T")
        return type.copy(bounds = listOf(KOTLIN_COMPARABLE.parameterizedBy(type)))
    }

    fun aggregateCall(
        terminal: String,
        function: String,
        column: String,
        groupBy: String,
        transform: CodeBlock,
    ): CodeBlock = codeBlock {
        add("return _readQueryEvaluator.rawAggregate(\n")
        indent()
        add("captureQuery = { captureEntityQuery() },\n")
        add("terminal = %S,\n", terminal)
        add("function = %T.%L,\n", AGG_FUNCTION, function)
        add("column = %L,\n", column)
        add("groupBy = %L,\n", groupBy)
        unindent()
        add(") { rows ->\n")
        indent()
        add(transform)
        unindent()
        add("}\n")
    }

    fun scalar(
        name: String,
        function: String,
        column: ParameterSpec,
        returnType: TypeName,
        typeVariables: List<TypeVariableName>,
    ) {
        specs += function(name, returnType = READ_RESULT.parameterizedBy(returnType)) {
            addAnnotation(suppress)
            addTypeVariables(typeVariables)
            addParameter(column)
            addCode(
                aggregateCall(
                    terminal = name,
                    function = function,
                    column = "column.name",
                    groupBy = "null",
                    transform = CodeBlock.of("rows.single().value as %T\n", returnType),
                ),
            )
        }
    }

    run {
        val type = comparableT()
        scalar(
            "rawMin",
            "MIN",
            parameter("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, type)),
            type.copy(nullable = true),
            listOf(type),
        )
    }
    run {
        val type = comparableT()
        scalar(
            "rawMax",
            "MAX",
            parameter("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, type)),
            type.copy(nullable = true),
            listOf(type),
        )
    }
    scalar(
        "rawSum",
        "SUM",
        parameter("column", INTEGRAL_COLUMN.parameterizedBy(entityClass, STAR)),
        LONG.copy(nullable = true),
        emptyList(),
    )
    scalar(
        "rawSum",
        "SUM",
        parameter("column", FLOATING_COLUMN.parameterizedBy(entityClass, STAR)),
        DOUBLE.copy(nullable = true),
        emptyList(),
    )
    scalar(
        "rawAvg",
        "AVG",
        parameter("column", NUMERIC_COLUMN.parameterizedBy(entityClass, STAR)),
        DOUBLE.copy(nullable = true),
        emptyList(),
    )

    fun grouped(
        name: String,
        function: String,
        valueColumn: ParameterSpec?,
        bucketValueType: TypeName,
        valueTypeVariables: List<TypeVariableName>,
    ) {
        for (nullableKey in listOf(false, true)) {
            val key = TypeVariableName("K")
            val groupColumn = if (nullableKey) NULLABLE_GROUPABLE_COLUMN else GROUPABLE_COLUMN
            val groupColumnType = groupColumn.parameterizedBy(entityClass, key)
            val keyType = if (nullableKey) key.copy(nullable = true) else key
            val buckets = LIST.parameterizedBy(AGG_BUCKET.parameterizedBy(keyType, bucketValueType))
            val decodedKey = if (nullableKey) {
                "groupBy.decodeKey(row.key)"
            } else {
                "groupBy.decodeKey(row.key)!!"
            }
            val columnName = if (valueColumn == null) "null" else "column.name"
            val parameters = listOfNotNull(
                parameter("groupBy", groupColumnType),
                valueColumn,
            )
            specs += function(name, returnType = READ_RESULT.parameterizedBy(buckets)) {
                addAnnotation(suppress)
                addTypeVariables(listOf(key) + valueTypeVariables)
                addParameters(parameters)
                addCode(
                    aggregateCall(
                        terminal = name,
                        function = function,
                        column = columnName,
                        groupBy = "groupBy.name",
                        transform = CodeBlock.of(
                            "rows.map { row -> %T(%L, row.value as %T) }\n",
                            AGG_BUCKET,
                            decodedKey,
                            bucketValueType,
                        ),
                    ),
                )
            }
        }
    }

    grouped("rawCountBy", "COUNT", null, LONG, emptyList())
    run {
        val type = comparableT()
        grouped(
            "rawMinBy",
            "MIN",
            parameter("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, type)),
            type.copy(nullable = true),
            listOf(type),
        )
    }
    run {
        val type = comparableT()
        grouped(
            "rawMaxBy",
            "MAX",
            parameter("column", COMPARABLE_COLUMN.parameterizedBy(entityClass, type)),
            type.copy(nullable = true),
            listOf(type),
        )
    }
    grouped(
        "rawSumBy",
        "SUM",
        parameter("column", INTEGRAL_COLUMN.parameterizedBy(entityClass, STAR)),
        LONG.copy(nullable = true),
        emptyList(),
    )
    grouped(
        "rawSumBy",
        "SUM",
        parameter("column", FLOATING_COLUMN.parameterizedBy(entityClass, STAR)),
        DOUBLE.copy(nullable = true),
        emptyList(),
    )
    grouped(
        "rawAvgBy",
        "AVG",
        parameter("column", NUMERIC_COLUMN.parameterizedBy(entityClass, STAR)),
        DOUBLE.copy(nullable = true),
        emptyList(),
    )

    return specs
}
