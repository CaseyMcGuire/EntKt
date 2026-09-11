package entkt.runtime.driver

import java.util.concurrent.CancellationException

/** Classify only the driver call; keep query preparation, entity decoding, and privacy outside this block. */
internal inline fun <Value> DatabaseDriver.executeRead(block: () -> Value): Value = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    val conflict = classifyConflictException(e)
    if (conflict != null) {
        throw conflict
    }
    throw e
}
