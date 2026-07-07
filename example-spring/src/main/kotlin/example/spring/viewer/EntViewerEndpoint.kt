package example.spring.viewer

import entkt.runtime.privacy.PrivacyContext
import entkt.runtime.privacy.Viewer
import entkt.viewer.EntViewer
import entkt.viewer.spring.EntViewerPrincipalResolver
import example.ent.EntClient
import example.ent.GeneratedEntViewerRegistry
import example.spring.auth.AuthContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/**
 * Declares the entkt viewer; `ent-viewer-spring`'s auto-configuration mounts
 * it at its configured path — the module on the classpath does nothing until
 * this bean exists, so the security-critical configuration (authorize,
 * privacyContext) stays right here in the application.
 *
 * Access rule for this example: any authenticated user (an `X-User-Id`
 * header, via [AuthContext]). A real application would gate on an admin
 * check. Rows are whatever that user's own privacy context can read — the
 * viewer adds no privileges.
 */
@Configuration
class EntViewerConfig {

    @Bean
    fun entViewer(client: EntClient): EntViewer<EntClient> =
        EntViewer(client, GeneratedEntViewerRegistry) {
            path = "/_ent"
            authorize { request -> request.principal != null }
            privacyContext { request ->
                val userId = request.principal as? UUID
                PrivacyContext(if (userId != null) Viewer.User(userId) else Viewer.Anonymous)
            }
        }

    /** Bridge the example's header-based auth into the viewer's principal. */
    @Bean
    fun entViewerPrincipalResolver(auth: AuthContext): EntViewerPrincipalResolver =
        EntViewerPrincipalResolver { auth.userId }
}
