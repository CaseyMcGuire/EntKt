package entkt.flyway

import entkt.migrations.MigrationOp
import entkt.migrations.describeOp
import java.nio.file.Path

/**
 * Result of [FlywayMigrationWorkflow.validate]. Reports schema drift
 * between the Flyway-migrated shadow database and the desired entkt
 * schemas without writing any files.
 */
data class DriftResult(
    val ops: List<MigrationOp>,
    val manual: List<MigrationOp>,
) {
    val hasDrift: Boolean get() = ops.isNotEmpty() || manual.isNotEmpty()
}

/** Result of [FlywayMigrationWorkflow.generate]. */
sealed class GenerateResult {
    /** No schema drift detected — no migration file written. */
    data object NoChanges : GenerateResult()

    /** A migration file was written. */
    data class Written(
        val filePath: Path,
        val ops: List<MigrationOp>,
        val manual: List<MigrationOp>,
        val verification: VerificationOutcome = VerificationOutcome.NotRequested,
    ) : GenerateResult()
}

/** Outcome of the optional post-generation verification step. */
enum class VerificationOutcome {
    /** Verification was not requested. */
    NotRequested,
    /** Verification ran and confirmed the migration resolves all drift. */
    Verified,
    /** Verification was skipped because manual ops require user-authored SQL. */
    SkippedManualOps,
}

/**
 * Thrown when verification fails — the generated migration did not
 * bring the shadow database to the desired schema state.
 */
class VerificationFailedException(
    val remainingOps: List<MigrationOp>,
    val remainingManual: List<MigrationOp>,
) : RuntimeException(
    buildString {
        appendLine("Verification failed: generated migration did not resolve all schema drift.")
        if (remainingOps.isNotEmpty()) {
            appendLine("Remaining auto ops:")
            for (op in remainingOps) appendLine("  - ${describeOp(op)}")
        }
        if (remainingManual.isNotEmpty()) {
            appendLine("Remaining manual ops:")
            for (op in remainingManual) appendLine("  - ${describeOp(op)}")
        }
    },
)
