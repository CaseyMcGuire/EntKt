package example.spring.web

import entkt.runtime.result.EntMutationPrivacyDeniedException
import entkt.runtime.result.EntPrivacyDeniedException
import entkt.runtime.result.EntTargetAbsentException
import entkt.runtime.result.EntUnexpectedMutationException
import entkt.runtime.result.EntValidationException
import example.spring.auth.AccessDeniedException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ErrorHandler {

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ResponseEntity<Map<String, String>> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(mapOf("error" to (ex.message ?: "Access denied")))
    }

    /**
     * A read LOAD denial thrown by `getOrThrow()`. The exception's
     * denial payload (entity keys, rule-supplied reasons) is trusted
     * diagnostic data — never echo it to untrusted clients; respond
     * with a generic message only.
     */
    @ExceptionHandler(EntPrivacyDeniedException::class)
    fun handleReadPrivacyDenied(ex: EntPrivacyDeniedException): ResponseEntity<Map<String, String>> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(mapOf("error" to "Access denied"))
    }

    /**
     * A mutation privacy denial thrown by `getOrThrow()`. Same
     * disclosure rule as reads: the key and rule-supplied reason stay
     * server-side.
     */
    @ExceptionHandler(EntMutationPrivacyDeniedException::class)
    fun handleMutationPrivacyDenied(
        ex: EntMutationPrivacyDeniedException,
    ): ResponseEntity<Map<String, String>> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(mapOf("error" to "Access denied"))
    }

    /**
     * `update(id)` against a missing or vanished row — the canonical
     * replacement for the old null-returning save, mapped to the same
     * 404 the null-check produced.
     */
    @ExceptionHandler(EntTargetAbsentException::class)
    fun handleTargetAbsent(ex: EntTargetAbsentException): ResponseEntity<Map<String, String>> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(mapOf("error" to "Not found"))
    }

    /** Schema/validation-rule rejection of a mutation — a client error. */
    @ExceptionHandler(EntValidationException::class)
    fun handleValidation(ex: EntValidationException): ResponseEntity<Map<String, String>> {
        val message = ex.violations.joinToString("; ") { it.message }
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to message.ifBlank { "Invalid request" }))
    }

    /**
     * An application callback (hook) exception captured by a mutation
     * terminal and rethrown by `getOrThrow()`. The example's hooks
     * reject bad requests with `require(...)`, which used to propagate
     * as a bare [IllegalArgumentException] → 400; unwrap the cause to
     * preserve that mapping. Anything else is an internal error.
     */
    @ExceptionHandler(EntUnexpectedMutationException::class)
    fun handleUnexpectedMutation(
        ex: EntUnexpectedMutationException,
    ): ResponseEntity<Map<String, String>> {
        val cause = ex.cause
        return if (cause is IllegalArgumentException) {
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to (cause.message ?: "Invalid request")))
        } else {
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to "Internal error"))
        }
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(mapOf("error" to (ex.message ?: "Invalid request")))
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(ex: IllegalStateException): ResponseEntity<Map<String, String>> {
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(mapOf("error" to (ex.message ?: "Internal error")))
    }
}
