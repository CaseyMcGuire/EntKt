package entkt.codegen.mutation

import entkt.codegen.apiName
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import entkt.codegen.kotlinpoet.function
import entkt.codegen.kotlinpoet.interfaceType
import entkt.codegen.kotlinpoet.kotlinFile
import entkt.codegen.kotlinpoet.property
import entkt.codegen.metadata.computeEdgeFks
import entkt.codegen.metadata.resolvedTypeName
import entkt.codegen.metadata.scalarFields
import entkt.codegen.metadata.toTypeName
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
 *   contract). Hooks that need to inspect pending update state should use
 *   `beforeUpdate` and read `ctx.patch.title`, which has explicit
 *   `FieldPatch.Unset` / `Set` / `Set(null)` semantics.
 *
 * In short: use `beforeSave` when you only need to **write**
 * field/FK values that apply uniformly to create and update. Use
 * `beforeCreate` / `beforeUpdate` for phase-specific reads, patch
 * inspection, or `unset{Field}()`.
 *
 * Also generates two restricted hook-facing views that extend the
 * shared `Mutation` interface. Both views are
 * **runtime-enforced** through private anonymous adapters generated at
 * the lifecycle boundary:
 *
 * - `${SchemaName}CreateMutationView` — the typed surface for
 *   `beforeCreate` hook lambdas. Adds immutable scalar fields
 *   (writable on create only). The hook receives a private
 *   adapter whose runtime type implements only this view. The generated
 *   repository forwards its reads and writes to the state-only
 *   `${SchemaName}CreateDraft`; neither the draft nor its executable
 *   `CreateMutation` wrapper is exposed to the hook.
 *
 * - `${SchemaName}UpdateMutationView` — the typed surface for
 *   `beforeUpdate` hook lambdas (via `ctx.mutation`). Adds
 *   `unset{Field}()` patch operations and the `pendingEdges`
 *   aggregator. Same private-adapter pattern as the create
 *   side: a hook that casts `ctx.mutation` to `${SchemaName}Update`
 *   throws `ClassCastException`.
 *
 * `beforeSave` hooks on both create and update receive a
 * `${SchemaName}Mutation`-typed adapter — the shared writable surface,
 * with no view-specific extensions reachable.
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
        val pendingEdgeOpsClass = ClassName(packageName, "${schemaName}PendingEdgeOps")
        // Backing FK columns flow through `edgeFks` so the interface
        // exposes them with relationship nullability (required → non-null).
        val fields = scalarFields(schema)
        val mutableFields = fields.filter { !it.immutable }
        val immutableFields = fields.filter { it.immutable }
        val edgeFks = computeEdgeFks(schema, schemaNames)
        // Field-backed FKs inherit backing-field immutability. Immutable
        // FKs are create-only writable — they belong on `CreateMutationView`
        // (which extends `Mutation`) but not on `Mutation` itself or on
        // `UpdateMutationView`.
        val mutableEdgeFks = edgeFks.filter { !it.immutable }
        val immutableEdgeFks = edgeFks.filter { it.immutable }

        val mutationInterface = interfaceType(interfaceName) {
            for (field in mutableFields) {
                addProperty(
                    mutableMutationProperty(
                        field.apiName,
                        field.resolvedTypeName().copy(nullable = true),
                        field.comment,
                    ),
                )
            }
            for (fk in mutableEdgeFks) {
                addProperty(
                    mutableMutationProperty(
                        fk.propertyName,
                        fk.idType.toTypeName().copy(nullable = !fk.required),
                        fk.comment,
                    ),
                )
            }
        }

        // The restricted hook-facing view passed to `beforeCreate`.
        // Extends `Mutation` and adds the create-only writable surface:
        // immutable scalar fields plus immutable field-backed FKs.
        // Hides `save()`, `client`, `driver`, hook lists, the
        // staging/assigned private fields, and any other concrete-builder
        // surface that hooks must not reach.
        val createView = interfaceType(createViewName) {
            addSuperinterface(ClassName(packageName, interfaceName))
            for (field in immutableFields) {
                addProperty(
                    mutableMutationProperty(
                        field.apiName,
                        field.resolvedTypeName().copy(nullable = true),
                        field.comment,
                    ),
                )
            }
            for (fk in immutableEdgeFks) {
                addProperty(
                    mutableMutationProperty(
                        fk.propertyName,
                        fk.idType.toTypeName().copy(nullable = !fk.required),
                        fk.comment,
                    ),
                )
            }
        }

        // The restricted hook-facing view passed to `beforeUpdate`
        // (via `ctx.mutation`). Extends `Mutation` and adds unset
        // semantics only for mutable scalars and mutable FKs — there's
        // no update surface for immutable values.
        val updateView = interfaceType(updateViewName) {
            addSuperinterface(ClassName(packageName, interfaceName))
            for (field in mutableFields) addFunction(unsetSpec(field.apiName))
            for (fk in mutableEdgeFks) addFunction(unsetSpec(fk.propertyName))
            // Read-only `pendingEdges` aggregator on the
            // hook-facing view. Hooks read pending link-table M2M edge ops
            // through `ctx.mutation.pendingEdges` (or `ctx.pendingEdges`);
            // they cannot mutate the underlying op log — the view does NOT
            // expose the per-edge mutator surface (`add`/`remove`/`set`).
            property("pendingEdges", pendingEdgeOpsClass)
        }

        return kotlinFile(packageName, interfaceName) {
            addType(mutationInterface)
            addType(createView)
            addType(updateView)
        }
    }

    /** Property shape shared by the mutation interfaces' writable fields. */
    private fun mutableMutationProperty(
        name: String,
        type: TypeName,
        kdoc: String?,
    ): PropertySpec = property(name, type) {
        mutable(true)
        if (kdoc != null) addKdoc("%L", kdoc)
    }

    private fun unsetSpec(prop: String): FunSpec {
        val name = "unset${prop.replaceFirstChar { it.uppercaseChar() }}"
        return function(name) {
            addModifiers(KModifier.ABSTRACT)
        }
    }
}
