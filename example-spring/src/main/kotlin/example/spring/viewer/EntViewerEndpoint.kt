package example.spring.viewer

import entkt.viewer.EntViewer
import entkt.viewer.spring.EntViewerPrincipalResolver
import example.ent.EntClient
import example.ent.GeneratedEntViewerRegistry
import example.spring.auth.AuthContext
import example.spring.auth.viewerContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Declares the entkt viewer; `ent-viewer-spring`'s auto-configuration mounts
 * it at its configured path — the module on the classpath does nothing until
 * this bean exists, so the security-critical configuration (authorize,
 * viewerContext) stays right here in the application.
 *
 * Access rule for this example: any authenticated user (an `X-User-Id`
 * header, via [AuthContext]). A real application would gate on an admin
 * check. Rows are whatever that user's own viewer context can read — the
 * viewer adds no privileges.
 */
@Configuration
class EntViewerConfig {

    @Bean
    fun entViewer(client: EntClient, auth: AuthContext): EntViewer<EntClient> =
        EntViewer(client, GeneratedEntViewerRegistry) {
            path = "/_ent"
            authorize { request -> request.principal != null }
            viewerContext { auth.viewerContext() }
        }

    /** Bridge the example's header-based auth into the viewer's principal. */
    @Bean
    fun entViewerPrincipalResolver(auth: AuthContext): EntViewerPrincipalResolver =
        EntViewerPrincipalResolver { auth.userId }
}
