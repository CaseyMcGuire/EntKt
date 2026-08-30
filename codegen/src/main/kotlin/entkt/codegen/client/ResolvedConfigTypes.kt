package entkt.codegen.client

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

internal val ENTITY_HOOKS = ClassName("entkt.runtime.hook", "EntityHooks")
private val RESOLVED_ENTITY_HOOKS = ClassName("entkt.runtime.hook", "ResolvedEntityHooks")
private val RESOLVED_ENTITY_PRIVACY_CONFIG =
    ClassName("entkt.runtime.privacy", "ResolvedEntityPrivacyConfig")
private val RESOLVED_ENTITY_VALIDATION_CONFIG =
    ClassName("entkt.runtime.validation", "ResolvedEntityValidationConfig")

internal fun entityHooksType(packageName: String, schemaName: String): TypeName =
    ENTITY_HOOKS.parameterizedBy(
        ClassName(packageName, "${schemaName}Mutation"),
        ClassName(packageName, "${schemaName}CreateHookContext"),
        ClassName(packageName, "${schemaName}UpdateHookContext"),
        ClassName(packageName, schemaName),
    )

internal fun resolvedEntityHooksType(packageName: String, schemaName: String): TypeName =
    RESOLVED_ENTITY_HOOKS.parameterizedBy(
        ClassName(packageName, "${schemaName}Mutation"),
        ClassName(packageName, "${schemaName}CreateHookContext"),
        ClassName(packageName, "${schemaName}UpdateHookContext"),
        ClassName(packageName, schemaName),
    )

internal fun resolvedEntityPrivacyConfigType(packageName: String, schemaName: String): TypeName =
    RESOLVED_ENTITY_PRIVACY_CONFIG.parameterizedBy(
        ClassName(packageName, "${schemaName}LoadBatchPrivacyRule"),
        ClassName(packageName, "${schemaName}CreateBatchPrivacyRule"),
        ClassName(packageName, "${schemaName}UpdateBatchPrivacyRule"),
        ClassName(packageName, "${schemaName}DeleteBatchPrivacyRule"),
    )

internal fun resolvedEntityValidationConfigType(packageName: String, schemaName: String): TypeName =
    RESOLVED_ENTITY_VALIDATION_CONFIG.parameterizedBy(
        ClassName(packageName, "${schemaName}CreateBatchValidationRule"),
        ClassName(packageName, "${schemaName}UpdateBatchValidationRule"),
        ClassName(packageName, "${schemaName}DeleteBatchValidationRule"),
    )
