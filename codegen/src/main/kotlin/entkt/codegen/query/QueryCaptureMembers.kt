package entkt.codegen.query

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STAR
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import entkt.codegen.metadata.EdgeJoin
import entkt.codegen.metadata.toTypeName
import entkt.schema.EdgeKind

private val ENTKT_INTERNAL = ClassName("entkt.query", "EntktInternal")
private val ENTITY_MAPPING = ClassName("entkt.runtime.entity", "EntityMapping")
private val DATABASE_ROW = Map::class.asClassName().parameterizedBy(
    String::class.asClassName(),
    Any::class.asClassName().copy(nullable = true),
)
private val ENTITY_QUERY = ClassName("entkt.runtime.query", "EntityQuery")
private val QUERY_SOURCE = ClassName("entkt.runtime.query", "QuerySource")
private val EDGE_SELECTION = ClassName("entkt.runtime.query", "EdgeSelection")
private val EDGE_VISIBILITY = ClassName("entkt.runtime.query", "EdgeVisibility")
private val EDGE_MAPPING = ClassName("entkt.runtime.query", "EdgeMapping")
private val EDGE_TRAVERSAL = ClassName("entkt.runtime.query", "EdgeTraversal")
private val TO_ONE_EDGE_MAPPING = ClassName("entkt.runtime.query", "ToOneEdgeMapping")
private val TO_MANY_EDGE_MAPPING = ClassName("entkt.runtime.query", "ToManyEdgeMapping")
private val EDGE_STORAGE = ClassName("entkt.runtime.query", "EdgeStorage")
private val EDGE_STATE = ClassName("entkt.runtime.query", "EdgeState")
private val MINIMUM_BIND_PARAMETERS =
    MemberName("entkt.runtime.driver", "minimumBindParameters")

/** Mutable relational origin retained until the query is captured for execution. */
internal fun buildEntityQuerySourceProperty(entityClass: ClassName): PropertySpec =
    PropertySpec.builder(
        "entityQuerySource",
        QUERY_SOURCE.parameterizedBy(entityClass),
    )
        .addModifiers(KModifier.PRIVATE)
        .mutable(true)
        .initializer("%T.Root<%T>()", QUERY_SOURCE, entityClass)
        .build()

/** Replace the root origin when a generated `query{Name}` traversal creates this query. */
internal fun buildSetEntityQuerySource(entityClass: ClassName): FunSpec =
    FunSpec.builder("setEntityQuerySource")
        .addAnnotation(ENTKT_INTERNAL)
        .addModifiers(KModifier.INTERNAL)
        .addParameter("source", QUERY_SOURCE.parameterizedBy(entityClass))
        .addStatement("this.entityQuerySource = source")
        .build()

/** Capture caller query state and selected edges as one immutable recursive value. */
internal fun buildCaptureEntityQuery(resolved: ResolvedQuerySchema): FunSpec {
    val entityClass = resolved.entityClass
    val predicateType = ClassName("entkt.query", "Predicate").parameterizedBy(entityClass)
    val selectedEdgeType = EDGE_SELECTION.parameterizedBy(entityClass, STAR)
    val body = CodeBlock.builder()
        .add(
            "// This is a conservative lower bound, not the final rendered bind count.\n" +
                "// Checking it first rejects oversized collection operands from their O(1) size\n" +
                "// before EntityQuery snapshots can iterate or copy their elements.\n",
        )
        .add(
            "val minimumRequiredBindParameters = %M(predicates) +\n" +
                "  %M(structuralPredicates)\n",
            MINIMUM_BIND_PARAMETERS,
            MINIMUM_BIND_PARAMETERS,
        )
        .add(
            "driver.requireBindCapacity(minimumRequiredBindParameters, %T.TABLE)\n",
            entityClass,
        )
        .add("val selectedEdges = buildList<%T> {\n", selectedEdgeType)

    for (edge in resolved.edges) {
        if (edge.join == null) continue
        body.add("  %L?.let { selectedQuery ->\n", edge.eagerPropName)
        body.add("    add(\n")
        body.add("      %T(\n", EDGE_SELECTION)
        body.add("        edge = %L,\n", edge.mappingName)
        body.add("        target = selectedQuery.captureEntityQuery(),\n")
        body.add(
            "        visibility = if (%LFilterVisible) %T.FILTER_INVISIBLE else %T.REQUIRE_VISIBLE,\n",
            edge.eagerPropName,
            EDGE_VISIBILITY,
            EDGE_VISIBILITY,
        )
        body.add("      ),\n")
        body.add("    )\n")
        body.add("  }\n")
    }

    body.add("}\n")
    body.add("return %T(\n", ENTITY_QUERY)
    body.add("  entity = GeneratedEntityMapping,\n")
    body.add("  source = entityQuerySource,\n")
    body.add("  predicates = predicates,\n")
    body.add("  orderBy = orderFields,\n")
    body.add("  limit = queryLimit,\n")
    body.add("  offset = queryOffset,\n")
    body.add("  edges = selectedEdges,\n")
    body.add("  structuralPredicates = structuralPredicates,\n")
    body.add(")\n")

    return FunSpec.builder("captureEntityQuery")
        .addAnnotation(ENTKT_INTERNAL)
        .addModifiers(KModifier.INTERNAL)
        .addParameter(
            com.squareup.kotlinpoet.ParameterSpec.builder(
                "structuralPredicates",
                List::class.asClassName().parameterizedBy(predicateType),
            )
                .defaultValue("emptyList()")
                .build(),
        )
        .returns(ENTITY_QUERY.parameterizedBy(entityClass))
        .addCode(body.build())
        .build()
}

/** Generate the typed entity and edge mappings referenced by captured queries. */
internal fun buildEntityQueryMappings(resolved: ResolvedQuerySchema): List<TypeSpec> = buildList {
    add(buildEntityMapping(resolved))
    resolved.edges
        .mapTo(this) { buildEdgeMapping(resolved, it, it.join) }
}

private fun buildEntityMapping(resolved: ResolvedQuerySchema): TypeSpec {
    val entityClass = resolved.entityClass
    return TypeSpec.objectBuilder("GeneratedEntityMapping")
        .addAnnotation(ENTKT_INTERNAL)
        .addModifiers(KModifier.INTERNAL)
        .addSuperinterface(ENTITY_MAPPING.parameterizedBy(entityClass))
        .addProperty(
            PropertySpec.builder("entityName", String::class)
                .addModifiers(KModifier.OVERRIDE)
                .initializer("%S", resolved.schemaName)
                .build(),
        )
        .addProperty(
            PropertySpec.builder("clientName", String::class)
                .addModifiers(KModifier.OVERRIDE)
                .initializer("%S", resolved.schema.clientName)
                .build(),
        )
        .addProperty(
            PropertySpec.builder(
                "entityClass",
                ClassName("kotlin.reflect", "KClass").parameterizedBy(entityClass),
            )
                .addModifiers(KModifier.OVERRIDE)
                .initializer("%T::class", entityClass)
                .build(),
        )
        .addProperty(
            PropertySpec.builder("table", String::class)
                .addModifiers(KModifier.OVERRIDE)
                .initializer("%T.TABLE", entityClass)
                .build(),
        )
        .addFunction(
            FunSpec.builder("decode")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("row", DATABASE_ROW)
                .returns(entityClass)
                .addStatement("return %T.fromRow(row)", entityClass)
                .build(),
        )
        .addFunction(
            FunSpec.builder("edgeByStorageName")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("storageName", String::class)
                .returns(EDGE_MAPPING.parameterizedBy(entityClass, STAR).copy(nullable = true))
                .addCode(
                    CodeBlock.builder()
                        .add("return when (storageName) {\n")
                        .apply {
                            for (edge in resolved.edges) {
                                add("  %S -> %L\n", edge.name, edge.mappingName)
                            }
                        }
                        .add("  else -> null\n")
                        .add("}\n")
                        .build(),
                )
                .build(),
        )
        .build()
}

private fun buildEdgeMapping(
    resolved: ResolvedQuerySchema,
    edge: ResolvedQueryEdge,
    join: EdgeJoin?,
): TypeSpec {
    val sourceClass = resolved.entityClass
    val targetClass = edge.targetClass
    val toOne = edge.edge.kind is EdgeKind.BelongsTo || edge.edge.kind is EdgeKind.HasOne
    val mappingInterface = when {
        join == null -> EDGE_MAPPING
        toOne -> TO_ONE_EDGE_MAPPING
        else -> TO_MANY_EDGE_MAPPING
    }

    return TypeSpec.objectBuilder(edge.mappingName)
        .addAnnotation(ENTKT_INTERNAL)
        .addModifiers(KModifier.INTERNAL)
        .addSuperinterface(mappingInterface.parameterizedBy(sourceClass, targetClass))
        .addProperty(
            PropertySpec.builder("name", String::class)
                .addModifiers(KModifier.OVERRIDE)
                .initializer("%S", edge.publicName)
                .build(),
        )
        .addProperty(
            PropertySpec.builder("storageName", String::class)
                .addModifiers(KModifier.OVERRIDE)
                .initializer("%S", edge.name)
                .build(),
        )
        .addProperty(
            PropertySpec.builder("source", ENTITY_MAPPING.parameterizedBy(sourceClass))
                .addModifiers(KModifier.OVERRIDE)
                .initializer("GeneratedEntityMapping")
                .build(),
        )
        .addProperty(
            PropertySpec.builder("target", ENTITY_MAPPING.parameterizedBy(targetClass))
                .addModifiers(KModifier.OVERRIDE)
                .initializer("%T.GeneratedEntityMapping", edge.targetQueryClass)
                .build(),
        )
        .addProperty(
            PropertySpec.builder(
                "traversal",
                EDGE_TRAVERSAL.parameterizedBy(sourceClass).copy(nullable = true),
            )
                .addModifiers(KModifier.OVERRIDE)
                .initializer(edgeTraversalInitializer(edge, join, sourceClass))
                .build(),
        )
        .apply {
            if (join != null) {
                addProperty(
                    PropertySpec.builder("storageStrategy", EDGE_STORAGE.parameterizedBy(sourceClass, targetClass))
                        .addModifiers(KModifier.OVERRIDE)
                        .initializer(edgeStorageInitializer(resolved, edge, join))
                        .build(),
                )
                addFunction(buildAttachFunction(edge, sourceClass, targetClass, toOne))
            }
        }
        .build()
}

private fun edgeTraversalInitializer(
    edge: ResolvedQueryEdge,
    join: EdgeJoin?,
    sourceClass: ClassName,
): CodeBlock = when {
    edge.isManyToMany -> CodeBlock.of(
        "%T.ManyToMany<%T>(sourceStorageName = %S, selectedColumn = %S)",
        EDGE_TRAVERSAL,
        sourceClass,
        edge.name,
        join?.sourceColumn ?: "id",
    )

    edge.inverse != null && join != null -> CodeBlock.of(
        "%T.Direct<%T>(inverseStorageName = %S, selectedColumn = %S)",
        EDGE_TRAVERSAL,
        sourceClass,
        edge.inverse.name,
        join.sourceColumn,
    )

    else -> CodeBlock.of("null")
}

private fun edgeStorageInitializer(
    resolved: ResolvedQuerySchema,
    edge: ResolvedQueryEdge,
    join: EdgeJoin,
): CodeBlock {
    val sourceClass = resolved.entityClass
    val targetClass = edge.targetClass
    return when (edge.edge.kind) {
        is EdgeKind.BelongsTo -> {
            val foreignKey = resolved.edgeFks.singleOrNull { it.columnName == join.sourceColumn }
                ?: error(
                    "Edge '${edge.publicName}' references source column '${join.sourceColumn}', " +
                        "which has no generated FK property on '${resolved.schemaName}'.",
                )
            CodeBlock.of(
                "%T.ForeignKeyOnSource<%T, %T, %T>(sourceColumn = %S, targetColumn = %S, sourceForeignKey = { it.%L }, targetKey = { it.id })",
                EDGE_STORAGE,
                sourceClass,
                targetClass,
                foreignKey.idType.toTypeName(),
                join.sourceColumn,
                join.targetColumn,
                foreignKey.propertyName,
            )
        }

        is EdgeKind.HasOne, is EdgeKind.HasMany -> {
            val foreignKey = edge.targetEdgeFks.singleOrNull { it.columnName == join.targetColumn }
                ?: error(
                    "Edge '${edge.publicName}' references target column '${join.targetColumn}', " +
                        "which has no generated FK property on '${edge.targetName}'.",
                )
            CodeBlock.of(
                "%T.ForeignKeyOnTarget<%T, %T, %T>(sourceColumn = %S, targetColumn = %S, sourceKey = { it.id }, targetForeignKey = { it.%L })",
                EDGE_STORAGE,
                sourceClass,
                targetClass,
                foreignKey.idType.toTypeName(),
                join.sourceColumn,
                join.targetColumn,
                foreignKey.propertyName,
            )
        }

        is EdgeKind.ManyToMany -> {
            val junctionEntityClass = checkNotNull(edge.junctionEntityClass)
            val junctionQueryClass = checkNotNull(edge.junctionQueryClass)
            CodeBlock.of(
                "%T.Junction<%T, %T, %T, %T, %T>(table = %S, sourceColumn = %S, targetColumn = %S, junctionEntity = %T.GeneratedEntityMapping, sourceKey = { it.id }, targetKey = { it.id })",
                EDGE_STORAGE,
                sourceClass,
                targetClass,
                junctionEntityClass,
                resolved.schema.id().type.toTypeName(),
                edge.edge.target.id().type.toTypeName(),
                checkNotNull(join.junctionTable),
                checkNotNull(join.junctionSourceColumn),
                checkNotNull(join.junctionTargetColumn),
                junctionQueryClass,
            )
        }
    }
}

private fun buildAttachFunction(
    edge: ResolvedQueryEdge,
    sourceClass: ClassName,
    targetClass: ClassName,
    toOne: Boolean,
): FunSpec {
    val targetType = if (toOne) {
        targetClass.copy(nullable = true)
    } else {
        List::class.asClassName().parameterizedBy(targetClass)
    }
    val parameterName = if (toOne) "target" else "targets"
    return FunSpec.builder("attach")
        .addModifiers(KModifier.OVERRIDE)
        .addParameter("source", sourceClass)
        .addParameter(parameterName, targetType)
        .returns(sourceClass)
        .addStatement(
            "return source.copy(edges = source.edges.copy(%L = %T.Loaded(%L)))",
            edge.edgePropName,
            EDGE_STATE,
            parameterName,
        )
        .build()
}
