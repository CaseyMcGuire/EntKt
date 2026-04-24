package entkt.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeSpec
import entkt.schema.EntSchema
import entkt.schema.Field
import entkt.schema.FieldType
import entkt.schema.UpdateDefault

private val ENTKT_DSL = ClassName("entkt.schema", "EntktDsl")
private val DRIVER = ClassName("entkt.runtime", "Driver")
private val ENT_CLIENT_NAME = "EntClient"
private val PRIVACY_CONTEXT = ClassName("entkt.runtime", "PrivacyContext")


class UpdateGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val className = "${schemaName}Update"
        val fields = schema.fields()
        val mixinFields = schema.mixins().flatMap { it.fields() }
        val allFields = fields + mixinFields
        val mutableFields = allFields.filter { !it.immutable }
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val entityClass = ClassName(packageName, schemaName)
        val updateClass = ClassName(packageName, className)
        val mutationClass = ClassName(packageName, "${schemaName}Mutation")
        val clientClass = ClassName(packageName, ENT_CLIENT_NAME)

        val beforeSaveHookType = hookListType(mutationClass)
        val beforeUpdateHookType = hookListType(updateClass)
        val afterUpdateHookType = hookListType(entityClass)

        val typeSpec = TypeSpec.classBuilder(className)
            .addAnnotation(AnnotationSpec.builder(ENTKT_DSL).build())
            .addSuperinterface(mutationClass)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("driver", DRIVER)
                    .addParameter("client", clientClass)
                    .addParameter("entity", entityClass)
                    .addParameter("beforeSaveHooks", beforeSaveHookType)
                    .addParameter("beforeUpdateHooks", beforeUpdateHookType)
                    .addParameter("afterUpdateHooks", afterUpdateHookType)
                    .build()
            )
            .addProperty(
                PropertySpec.builder("driver", DRIVER)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("driver")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("client", clientClass)
                    .initializer("client")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("entity", entityClass)
                    .initializer("entity")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("beforeSaveHooks", beforeSaveHookType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("beforeSaveHooks")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("beforeUpdateHooks", beforeUpdateHookType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("beforeUpdateHooks")
                    .build()
            )
            .addProperty(
                PropertySpec.builder("afterUpdateHooks", afterUpdateHookType)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("afterUpdateHooks")
                    .build()
            )
            .addProperty(
                PropertySpec.builder(
                    "dirtyFields",
                    ClassName("kotlin.collections", "MutableSet").parameterizedBy(STRING),
                )
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("mutableSetOf()")
                    .build()
            )
            .addProperties(mutableFields.map { buildProperty(it) })
            .addProperties(edgeFks.map { buildEdgeFkProperty(it) })
            .addProperties(edgeFks.map { buildEdgeEntityProperty(it) })
            .addFunction(buildSaveFunction(schemaName, allFields, edgeFks))
            .addFunction(buildSaveOrThrowFunction(schemaName))
            .build()

        return FileSpec.builder(packageName, className)
            .addType(typeSpec)
            .build()
    }

    private fun buildProperty(field: Field): PropertySpec {
        val prop = toCamelCase(field.name)
        val typeName = field.resolvedTypeName().copy(nullable = true)
        val builder = PropertySpec.builder(prop, typeName)
            .addModifiers(KModifier.OVERRIDE)
            .mutable(true)
            .initializer("null")
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", typeName)
                    .addStatement("field = value")
                    .addStatement("dirtyFields.add(%S)", prop)
                    .build()
            )
        val comment = field.comment
        if (comment != null) builder.addKdoc("%L", comment)
        return builder.build()
    }

    private fun buildEdgeFkProperty(fk: EdgeFk): PropertySpec {
        val typeName = fk.idType.toTypeName().copy(nullable = true)
        return PropertySpec.builder(fk.propertyName, typeName)
            .addModifiers(KModifier.OVERRIDE)
            .mutable(true)
            .initializer("null")
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", typeName)
                    .addStatement("field = value")
                    .addStatement("dirtyFields.add(%S)", fk.propertyName)
                    .build()
            )
            .build()
    }

    /**
     * Assigning a target entity here also writes its id into the
     * underlying FK property. e.g. `author = alice` sets
     * `authorId = alice.id`.
     */
    private fun buildEdgeEntityProperty(fk: EdgeFk): PropertySpec {
        val targetClass = ClassName(packageName, fk.targetName).copy(nullable = true)
        val edgeProp = toCamelCase(fk.edgeName)
        return PropertySpec.builder(edgeProp, targetClass)
            .mutable(true)
            .initializer("null")
            .setter(
                FunSpec.setterBuilder()
                    .addParameter("value", targetClass)
                    .addStatement("field = value")
                    .addStatement("%L = value?.id", fk.propertyName)
                    .build()
            )
            .build()
    }

    /**
     * `save()` writes the builder's changes to the driver and returns
     * the refreshed entity — or null when the row has been deleted out
     * from under us. Each mutable field checks [dirtyFields] to decide
     * whether to use the builder's value or fall back to the entity's
     * current value — this lets callers explicitly set nullable fields
     * to null. Immutables are sourced straight from the entity (they
     * can't change) and included in the map so the fallback behavior
     * stays obvious — the driver's merge semantics make it a no-op
     * write.
     */
    private fun buildSaveFunction(
        schemaName: String,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        val builder = FunSpec.builder("save")
            .returns(entityClass.copy(nullable = true))

        // ---- Lifecycle hooks (before fallback so hooks can set fields). ----
        builder.addStatement("for (hook in beforeSaveHooks) hook(this)")
        builder.addStatement("for (hook in beforeUpdateHooks) hook(this)")

        for (field in allFields) {
            val prop = toCamelCase(field.name)
            if (field.immutable) {
                builder.addStatement("val %L = entity.%L", prop, prop)
            } else if (field.updateDefault != null) {
                builder.addCode(
                    "val %L = if (%S in dirtyFields) this.%L else %L\n",
                    prop,
                    prop,
                    prop,
                    updateDefaultCodeBlock(field),
                )
            } else {
                builder.addStatement(
                    "val %L = if (%S in dirtyFields) this.%L else entity.%L",
                    prop,
                    prop,
                    prop,
                    prop,
                )
            }
        }

        for (fk in edgeFks) {
            builder.addStatement(
                "val %L = if (%S in dirtyFields) this.%L else entity.%L",
                fk.propertyName,
                fk.propertyName,
                fk.propertyName,
                fk.propertyName,
            )
        }

        // ---- Required-field null checks (mutable fields only). ----
        // Dirty tracking makes all mutable locals nullable. If a required
        // field was explicitly set to null, catch it here with a domain
        // error instead of letting it NPE during candidate construction.
        for (field in allFields) {
            if (field.immutable) continue
            if (field.nullable) continue
            val prop = toCamelCase(field.name)
            builder.addStatement(
                "if (%L == null) throw IllegalStateException(%S)",
                prop,
                "${field.name} is required",
            )
        }
        for (fk in edgeFks) {
            if (!fk.required) continue
            builder.addStatement(
                "if (%L == null) throw IllegalStateException(%S)",
                fk.propertyName,
                "${fk.edgeName} is required",
            )
        }

        // ---- Field-level validation (mutable fields only). ----
        for (field in allFields) {
            if (field.immutable) continue
            if (field.validators.isEmpty()) continue
            val prop = toCamelCase(field.name)
            // All update locals are nullable (dirty tracking fallback).
            emitFieldValidation(builder, prop, field.name, field.validators, nullable = true)
        }

        val rowBuilder = CodeBlock.builder()
            .add("val values: Map<String, Any?> = mapOf(\n")
        for (field in allFields) {
            val prop = toCamelCase(field.name)
            val col = field.columnName
            if (field.type == FieldType.ENUM) {
                rowBuilder.add("  %S to %L?.name,\n", col, prop)
            } else {
                rowBuilder.add("  %S to %L,\n", col, prop)
            }
        }
        for (fk in edgeFks) {
            rowBuilder.add("  %S to %L,\n", fk.columnName, fk.propertyName)
        }
        rowBuilder.add(")\n")

        builder.addCode(rowBuilder.build())
        emitUpdatePrivacy(builder, schemaName, allFields, edgeFks)
        emitUpdateValidation(builder, schemaName)
        builder.addStatement(
            "val row = driver.update(%T.TABLE, entity.id, values) ?: return null",
            entityClass,
        )
        builder.addStatement("val updatedEntity = %T.fromRow(row)", entityClass)
        builder.addStatement("for (hook in afterUpdateHooks) hook(updatedEntity)")
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        builder.addStatement("client.%L.evaluateLoadPrivacy(privacy, updatedEntity)", repoPropName)
        builder.addStatement("return updatedEntity")

        return builder.build()
    }

    /**
     * Non-null variant: throws when the row has vanished. Useful from
     * callers that already know the entity exists (e.g. re-saving the
     * result of a recent query) and don't want to deal with the `?`.
     */
    private fun buildSaveOrThrowFunction(schemaName: String): FunSpec {
        val entityClass = ClassName(packageName, schemaName)
        return FunSpec.builder("saveOrThrow")
            .returns(entityClass)
            .addStatement(
                "return save() ?: throw IllegalStateException(%S)",
                "$schemaName row not found",
            )
            .build()
    }

    /**
     * Emit UPDATE validation enforcement: call the repo's
     * evaluateUpdateValidation with the entity and already-built candidate.
     */
    private fun emitUpdateValidation(
        builder: FunSpec.Builder,
        schemaName: String,
    ) {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        builder.addStatement("client.%L.evaluateUpdateValidation(entity, candidate)", repoPropName)
    }

    /**
     * Emit UPDATE privacy enforcement: build a WriteCandidate from the
     * resolved field locals and call the repo's evaluateUpdatePrivacy.
     */
    private fun emitUpdatePrivacy(
        builder: FunSpec.Builder,
        schemaName: String,
        allFields: List<Field>,
        edgeFks: List<EdgeFk>,
    ) {
        val repoPropName = pluralize(schemaName.replaceFirstChar { it.lowercase() })
        val candidateClass = ClassName(packageName, "${schemaName}WriteCandidate")
        builder.addStatement("val privacy = client.currentPrivacyContext()")
        val candidateArgs = mutableListOf<String>()
        for (field in allFields) {
            val propName = toCamelCase(field.name)
            candidateArgs.add("$propName = $propName")
        }
        for (fk in edgeFks) {
            candidateArgs.add("${fk.propertyName} = ${fk.propertyName}")
        }
        builder.addStatement(
            "val candidate = %T(${candidateArgs.joinToString(", ")})",
            candidateClass,
        )
        builder.addStatement("client.%L.evaluateUpdatePrivacy(privacy, entity, candidate)", repoPropName)
    }

    private fun updateDefaultCodeBlock(field: Field): CodeBlock {
        return when (field.updateDefault!!) {
            is UpdateDefault.Now -> {
                require(field.type == FieldType.TIME) {
                    "Field '${field.name}' has UpdateDefault.Now but type is ${field.type} — updateDefault is only valid on TIME fields"
                }
                CodeBlock.of("%T.now()", ClassName("java.time", "Instant"))
            }
        }
    }
}
