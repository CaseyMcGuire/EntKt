package example.spring.auth

import java.util.UUID

/**
 * Who a request runs as. The generated client is bound to this type by the
 * `viewerType` argument of the `generateEntkt` task, so privacy rules read it
 * as `context.viewer`.
 *
 * [userId] is null for an unauthenticated request.
 */
data class AppViewer(val userId: UUID?)
