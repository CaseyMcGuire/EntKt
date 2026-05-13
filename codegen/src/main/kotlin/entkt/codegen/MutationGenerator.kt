package entkt.codegen

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import entkt.schema.EntSchema

/**
 * Generates a `${SchemaName}Mutation` interface per entity. Both the
 * generated Create and Update builders implement this interface, which
 * exposes all **mutable** field properties (immutable fields are excluded
 * since they can't be changed on update). Edge FK properties are included.
 *
 * This lets users register `onBeforeSave` hooks that fire on both create
 * and update — validation, timestamp injection, etc. — without
 * duplicating the logic.
 *
 * **Conceptually write-only.** The `Mutation` interface is intentionally
 * the *shared writable surface* for `beforeSave` hooks. It has no
 * patch-model operations (`unset{Field}()`, reading pending-state)
 * because those are update-specific — creates don't have a `dirtyFields`
 * patch to unset from. Reading property getters is allowed by the
 * Kotlin type system (declared `var`) but the read semantics differ
 * between Create and Update implementations:
 *
 * - On Create, `m.title` returns the staged value (or `null` if nothing
 *   has been assigned).
 * - On Update, `m.title` **throws** when the field is not in
 *   `dirtyFields`, because a default-null getter would conflate
 *   `Unset` and explicit `Set(null)` (see the id-based update roots
 *   RFC). Hooks that need to inspect pending update state should use
 *   `beforeUpdate` and read `ctx.patch.title`, which has explicit
 *   `FieldPatch.Unset` / `Set` / `Set(null)` semantics.
 *
 * In short: use `beforeSave` when you only need to **write**
 * field/FK values that apply uniformly to create and update. Use
 * `beforeCreate` / `beforeUpdate` for phase-specific reads, patch
 * inspection, or `unset{Field}()`.
 *
 * Also generates two restricted hook-facing views that extend the
 * shared `Mutation` interface:
 *
 * - `${SchemaName}CreateMutationView` — passed to `beforeCreate` hooks.
 *   Adds immutable scalar fields (writable on create only). Hides the
 *   concrete-builder surface (`save()`, `client`, `driver`, hook lists,
 *   private staging fields) so hooks can't re-enter the save pipeline.
 *
 * - `${SchemaName}UpdateMutationView` — passed to `beforeUpdate` hooks
 *   via `ctx.mutation`. Adds `unset{Field}()` patch operations.
 *
 * The Update view's `unset` methods live only on the update side because
 * the patch model they remove from is update-specific.
 */
internal class MutationGenerator(
    private val packageName: String,
) {

    fun generate(
        schemaName: String,
        schema: EntSchema,
        schemaNames: Map<EntSchema, String> = emptyMap(),
    ): FileSpec {
        val interfaceName = "${schemaName}Mutation"
        val createViewName = "${schemaName}CreateMutationView"
        val updateViewName = "${schemaName}UpdateMutationView"
        // Backing FK columns flow through `edgeFks` so the interface
        // exposes them with relationship nullability (required → non-null).
        val fields = scalarFields(schema)
        val mutableFields = fields.filter { !it.immutable }
        val immutableFields = fields.filter { it.immutable }
        val edgeFks = computeEdgeFks(schema, schemaNames)

        val mutationInterface = TypeSpec.interfaceBuilder(interfaceName)

        for (field in mutableFields) {
            val typeName = field.resolvedTypeName().copy(nullable = true)
            val prop = PropertySpec.builder(toCamelCase(field.name), typeName)
                .mutable(true)
            val comment = field.comment
            if (comment != null) prop.addKdoc("%L", comment)
            mutationInterface.addProperty(prop.build())
        }

        for (fk in edgeFks) {
            val typeName = fk.idType.toTypeName().copy(nullable = !fk.required)
            mutationInterface.addProperty(
                PropertySpec.builder(fk.propertyName, typeName)
                    .mutable(true)
                    .build(),
            )
        }

        // The restricted hook-facing view passed to `beforeCreate`.
        // Extends `Mutation` and adds immutable scalar fields, which are
        // create-only writable. Hides `save()`, `client`, `driver`, the
        // hook lists, the staging/assigned private fields, and any other
        // concrete-builder surface that hooks must not reach.
        val createView = TypeSpec.interfaceBuilder(createViewName)
            .addSuperinterface(ClassName(packageName, interfaceName))
        for (field in immutableFields) {
            val typeName = field.resolvedTypeName().copy(nullable = true)
            val prop = PropertySpec.builder(toCamelCase(field.name), typeName)
                .mutable(true)
            val comment = field.comment
            if (comment != null) prop.addKdoc("%L", comment)
            createView.addProperty(prop.build())
        }

        // The restricted hook-facing view passed to `beforeUpdate`
        // (via `ctx.mutation`). Extends `Mutation` and adds unset
        // semantics for the patch model.
        val updateView = TypeSpec.interfaceBuilder(updateViewName)
            .addSuperinterface(ClassName(packageName, interfaceName))
        for (field in mutableFields) {
            updateView.addFunction(unsetSpec(toCamelCase(field.name)))
        }
        for (fk in edgeFks) {
            updateView.addFunction(unsetSpec(fk.propertyName))
        }

        return FileSpec.builder(packageName, interfaceName)
            .addType(mutationInterface.build())
            .addType(createView.build())
            .addType(updateView.build())
            .build()
    }

    private fun unsetSpec(prop: String): FunSpec {
        val name = "unset${prop.replaceFirstChar { it.uppercaseChar() }}"
        return FunSpec.builder(name)
            .addModifiers(com.squareup.kotlinpoet.KModifier.ABSTRACT)
            .build()
    }
}
