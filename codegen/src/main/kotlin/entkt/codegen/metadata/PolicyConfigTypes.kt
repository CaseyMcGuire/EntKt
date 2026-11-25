package entkt.codegen.metadata

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName

internal val ENTITY_PRIVACY_CONFIG = ClassName("entkt.runtime.privacy", "EntityPrivacyConfig")
internal val ENTITY_VALIDATION_CONFIG = ClassName("entkt.runtime.validation", "EntityValidationConfig")

/** The runtime privacy configuration with one entity's batch rule types bound. */
internal fun entityPrivacyConfigType(packageName: String, schemaName: String): TypeName =
    ENTITY_PRIVACY_CONFIG.parameterizedBy(
        ClassName(packageName, "${schemaName}LoadBatchPrivacyRule"),
        ClassName(packageName, "${schemaName}CreateBatchPrivacyRule"),
        ClassName(packageName, "${schemaName}UpdateBatchPrivacyRule"),
        ClassName(packageName, "${schemaName}DeleteBatchPrivacyRule"),
    )

/** The runtime validation configuration with one entity's batch rule types bound. */
internal fun entityValidationConfigType(packageName: String, schemaName: String): TypeName =
    ENTITY_VALIDATION_CONFIG.parameterizedBy(
        ClassName(packageName, "${schemaName}CreateBatchValidationRule"),
        ClassName(packageName, "${schemaName}UpdateBatchValidationRule"),
        ClassName(packageName, "${schemaName}DeleteBatchValidationRule"),
    )
