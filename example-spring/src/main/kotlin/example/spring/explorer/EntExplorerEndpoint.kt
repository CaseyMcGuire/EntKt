package example.spring.explorer

import entkt.explorer.EntExplorer
import entkt.explorer.spring.EntExplorerPrincipalResolver
import example.ent.EntClient
import example.ent.GeneratedEntExplorerRegistry
import example.spring.auth.AppViewer
import example.spring.auth.AuthContext
import example.spring.auth.viewerContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Declares the entkt explorer; `ent-explorer-spring`'s auto-configuration mounts
 * it at its configured path — the module on the classpath does nothing until
 * this bean exists, so the security-critical configuration (authorize,
 * viewerContext) stays right here in the application.
 *
 * Access rule for this example: any authenticated user (an `X-User-Id`
 * header, via [AuthContext]). A real application would gate on an admin
 * check. Rows are whatever that user's own viewer context can read — the
 * explorer adds no privileges.
 */
@Configuration
class EntExplorerConfig {

    @Bean
    fun entExplorer(client: EntClient, auth: AuthContext): EntExplorer<AppViewer, EntClient> =
        EntExplorer(client, GeneratedEntExplorerRegistry) {
            path = "/_ent"
            authorize { request -> request.principal != null }
            viewerContext { auth.viewerContext() }
        }

    /** Bridge the example's header-based auth into the explorer's principal. */
    @Bean
    fun entExplorerPrincipalResolver(auth: AuthContext): EntExplorerPrincipalResolver =
        EntExplorerPrincipalResolver { auth.userId }
}
